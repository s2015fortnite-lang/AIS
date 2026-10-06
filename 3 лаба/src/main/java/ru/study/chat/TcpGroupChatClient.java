package ru.study.chat;

import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;

/** Сетевой адаптер прежнего интерфейса GroupChat. Один запрос в полёте на соединение. */
public final class TcpGroupChatClient implements GroupChat {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    public TcpGroupChatClient(String host, int port) throws IOException {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(10000);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
        } catch (IOException | RuntimeException e) { socket.close(); throw e; }
    }

    private synchronized List<String> call(String... request) {
        if (socket.isClosed()) throw new IllegalStateException("TCP-клиент закрыт");
        try {
            TcpProtocol.write(output, List.of(request));
            List<String> response = TcpProtocol.read(input);
            if (response == null) throw new EOFException("Сервер закрыл соединение");
            if (response.get(0).equals("ERR") && response.size() == 3) {
                switch (response.get(1)) {
                    case "ARGUMENT" -> throw new IllegalArgumentException(response.get(2));
                    case "STATE" -> throw new IllegalStateException(response.get(2));
                    default -> throw new ChatStorageException(response.get(2), null);
                }
            }
            if (!response.get(0).equals("OK")) throw new IOException("Некорректный ответ сервера");
            return response.subList(1, response.size());
        } catch (IOException e) {
            close(); // После тайм-аута нельзя принять старый ответ как ответ на новый запрос.
            throw new IllegalStateException("Ошибка TCP-соединения", e);
        }
    }

    @Override public void connect(String name) { call("CONNECT", name); }
    @Override public void disconnect(String name) { call("DISCONNECT", name); }
    @Override public void setNotificationsEnabled(String name, boolean enabled) {
        call("NOTIFY", name, Boolean.toString(enabled));
    }
    @Override public UserState getUserState(String name) {
        List<String> fields = call("STATE", name);
        return new UserState(fields.get(0), TcpProtocol.bool(fields.get(1)),
                Instant.parse(fields.get(2)), TcpProtocol.bool(fields.get(3)));
    }
    @Override public ChatMessage sendMessage(String name, String text) {
        return TcpProtocol.message(call("SEND", name, text), 0);
    }
    @Override public List<ChatMessage> receiveMessages(String name) { return messages(call("RECEIVE", name)); }
    @Override public List<ChatMessage> getHistory(String name) { return messages(call("HISTORY", name)); }

    private List<ChatMessage> messages(List<String> fields) {
        int count = Integer.parseInt(fields.get(0));
        if (count < 0 || fields.size() != 1L + count * 4L)
            throw new IllegalStateException("Некорректный список сообщений");
        List<ChatMessage> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(TcpProtocol.message(fields, 1 + i * 4));
        return List.copyOf(result);
    }

    @Override public synchronized void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
