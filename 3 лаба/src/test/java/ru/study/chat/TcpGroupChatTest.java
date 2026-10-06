package ru.study.chat;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class TcpGroupChatTest {
    @TempDir Path directory;
    private PersistentGroupChat storage;
    private TcpGroupChatServer server;
    private TcpGroupChatClient client;
    private static final Instant NOW = Instant.parse("2026-10-06T09:00:00Z");

    @BeforeEach void start() throws Exception {
        storage = new PersistentGroupChat(directory.resolve("chat"), Clock.fixed(NOW, ZoneOffset.UTC));
        server = new TcpGroupChatServer(storage, InetAddress.getLoopbackAddress(), 0);
        client = newClient();
    }

    private TcpGroupChatClient newClient() throws IOException {
        return new TcpGroupChatClient(InetAddress.getLoopbackAddress().getHostAddress(), server.port());
    }

    private Socket rawClient() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
        socket.setSoTimeout(5000);
        return socket;
    }

    @AfterEach void stop() {
        client.close(); server.close(); storage.close();
    }

    @Test void deliveryHistoryAndStateWorkThroughDifferentConnections() throws Exception {
        try (TcpGroupChatClient bob = newClient()) {
            client.connect("Анна"); bob.connect("Борис");
            client.connect("Виктор"); client.setNotificationsEnabled("Виктор", false);
            client.connect("Глеб"); client.disconnect("Глеб");
            ChatMessage message = client.sendMessage("Анна", "Всем привет!");
            assertEquals(List.of(message), bob.receiveMessages("Борис"));
            assertEquals(List.of(message), client.receiveMessages("Анна"));
            assertTrue(bob.receiveMessages("Борис").isEmpty());
            assertTrue(client.receiveMessages("Виктор").isEmpty());
            assertTrue(client.receiveMessages("Глеб").isEmpty());
            assertEquals(List.of(message), client.getHistory("Виктор"));
            assertEquals(new UserState("Анна", true, NOW, true), client.getUserState("Анна"));
            assertFalse(client.getUserState("Виктор").notificationsEnabled());
            assertFalse(client.getUserState("Глеб").online());
            client.setNotificationsEnabled("Виктор", true);
            ChatMessage next = bob.sendMessage("Борис", "Ответ");
            assertEquals(List.of(next), client.receiveMessages("Виктор"));
        }
    }

    @Test void errorsDoNotBreakConnectionOrModifyHistory() {
        assertThrows(IllegalArgumentException.class, () -> client.getHistory("Нет"));
        assertThrows(IllegalArgumentException.class, () -> client.connect(" "));
        client.connect("Анна");
        assertThrows(IllegalArgumentException.class, () -> client.sendMessage("Анна", ""));
        client.disconnect("Анна");
        assertThrows(IllegalStateException.class, () -> client.sendMessage("Анна", "Не отправится"));
        assertTrue(client.getHistory("Анна").isEmpty());
        client.connect("Анна");
        assertEquals("Можно", client.sendMessage("Анна", "Можно").text());
    }

    @Test void unicodeAndControlCharactersRoundTrip() {
        String name = "Анна\t\\\n\0🙂";
        String text = "Привет 🙂\nвторая строка\r\t\\\0конец";
        client.connect(name);
        ChatMessage message = client.sendMessage(name, text);
        assertEquals(name, message.sender()); assertEquals(text, message.text());
        assertEquals(List.of(message), client.receiveMessages(name));
        assertEquals(List.of(message), client.getHistory(name));
    }

    @Test void splitFramesAndMultipleFramesInOneWriteWork() throws Exception {
        try (Socket socket = rawClient()) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            TcpProtocol.write(frame, List.of("CONNECT", "Анна"));
            // Делим в том числе многобайтовые UTF-8 символы.
            for (byte b : frame.toByteArray()) {
                socket.getOutputStream().write(b); socket.getOutputStream().flush();
            }
            assertEquals(List.of("OK"), TcpProtocol.read(socket.getInputStream()));
            frame.reset();
            TcpProtocol.write(frame, List.of("STATE", "Анна"));
            TcpProtocol.write(frame, List.of("HISTORY", "Анна"));
            socket.getOutputStream().write(frame.toByteArray()); socket.getOutputStream().flush();
            assertEquals("Анна", TcpProtocol.read(socket.getInputStream()).get(1));
            assertEquals(List.of("OK", "0"), TcpProtocol.read(socket.getInputStream()));
        }
    }

    @Test void invalidCommandsReturnErrorsAndConnectionRemainsUsable() throws Exception {
        try (Socket socket = rawClient()) {
            for (List<String> command : List.of(List.of("BOGUS"), List.of("CONNECT"),
                    List.of("CONNECT", "Анна", "лишнее"), List.of("NOTIFY", "Анна", "yes"))) {
                TcpProtocol.write(socket.getOutputStream(), command);
                List<String> response = TcpProtocol.read(socket.getInputStream());
                assertEquals("ERR", response.get(0)); assertEquals("ARGUMENT", response.get(1));
            }
            TcpProtocol.write(socket.getOutputStream(), List.of("CONNECT", "Анна"));
            assertEquals(List.of("OK"), TcpProtocol.read(socket.getInputStream()));
        }
    }

    @Test void incompleteRequestIsNotExecuted() throws Exception {
        try (Socket socket = rawClient()) {
            socket.getOutputStream().write("CONNECT\tНезавершённый".getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            assertEquals(-1, socket.getInputStream().read());
        }
        assertThrows(IllegalArgumentException.class, () -> client.getUserState("Незавершённый"));
    }

    @Test void invalidEncodingClosesOnlyOffendingConnection() throws Exception {
        for (byte[] bytes : List.of(new byte[]{(byte) 0xC3, 0}, "CONNECT\tbad\\x\0".getBytes(StandardCharsets.UTF_8))) {
            try (Socket socket = rawClient()) {
                socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
                assertEquals(-1, socket.getInputStream().read());
            }
        }
        client.connect("Рабочий"); assertTrue(client.getUserState("Рабочий").online());
    }

    @Test void restartPreservesHistorySettingsAndUnreadMessagesThroughTcp() throws Exception {
        client.connect("Анна"); client.connect("Борис");
        client.setNotificationsEnabled("Борис", false);
        ChatMessage message = client.sendMessage("Анна", "После перезапуска");
        client.disconnect("Анна");
        stop(); start();
        assertFalse(client.getUserState("Анна").online());
        assertEquals(NOW, client.getUserState("Анна").lastConnectedAt());
        assertFalse(client.getUserState("Борис").notificationsEnabled());
        assertEquals(List.of(message), client.getHistory("Борис"));
        assertEquals(List.of(message), client.receiveMessages("Анна"));
        assertTrue(client.receiveMessages("Анна").isEmpty());
    }

    @Test void concurrentClientsDoNotLoseMessages() throws Exception {
        client.connect("Получатель");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> tasks = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                String name = "Клиент-" + i;
                tasks.add(pool.submit(() -> {
                    try (TcpGroupChatClient sender = newClient()) {
                        sender.connect(name); start.await();
                        for (int j = 0; j < 25; j++) sender.sendMessage(name, "Сообщение " + j);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        List<ChatMessage> received = client.receiveMessages("Получатель");
        assertEquals(100, received.size()); assertEquals(100, client.getHistory("Получатель").size());
        assertEquals(100, received.stream().map(ChatMessage::id).distinct().count());
    }

    @Test void serverCloseTerminatesActiveConnections() {
        server.close();
        assertThrows(IllegalStateException.class, () -> client.connect("Анна"));
        assertThrows(IllegalStateException.class, () -> client.connect("Анна"));
    }

    @Test void frameLimitRejectsUnboundedInput() {
        byte[] bytes = new byte[TcpProtocol.MAX_FRAME_BYTES + 1];
        Arrays.fill(bytes, (byte) 'x');
        assertThrows(IOException.class, () -> TcpProtocol.read(new ByteArrayInputStream(bytes)));
    }
}
