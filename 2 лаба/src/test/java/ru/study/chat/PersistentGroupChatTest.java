package ru.study.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentGroupChatTest {

    private static final Instant NOW = Instant.parse("2026-09-22T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void mainScenarioWorksWithDatabaseStorage() {
        Path database = temporaryDirectory.resolve("main-scenario");
        try (GroupChat chat = new PersistentGroupChat(database, CLOCK)) {
            chat.connect("Анна");
            chat.connect("Борис");
            chat.setNotificationsEnabled("Борис", false);

            ChatMessage message = chat.sendMessage("Анна", "Всем привет!");

            assertEquals(List.of(message), chat.receiveMessages("Анна"));
            assertTrue(chat.receiveMessages("Борис").isEmpty());
            assertEquals(List.of(message), chat.getHistory("Борис"));
        }
    }

    @Test
    void gracefulRestartRestoresCompleteState() {
        Path database = temporaryDirectory.resolve("graceful-restart");
        ChatMessage savedMessage;

        try (GroupChat firstProcess = new PersistentGroupChat(database, CLOCK)) {
            firstProcess.connect("Анна");
            firstProcess.connect("Борис");
            firstProcess.setNotificationsEnabled("Борис", false);
            savedMessage = firstProcess.sendMessage("Анна", "Сохраненное сообщение");
            firstProcess.disconnect("Анна");
        }

        try (GroupChat restartedProcess = new PersistentGroupChat(database, CLOCK)) {
            UserState anna = restartedProcess.getUserState("Анна");
            UserState boris = restartedProcess.getUserState("Борис");

            assertFalse(anna.online());
            assertEquals(NOW, anna.lastConnectedAt());
            assertTrue(boris.online());
            assertFalse(boris.notificationsEnabled());
            assertEquals(List.of(savedMessage), restartedProcess.getHistory("Борис"));
            assertEquals(List.of(savedMessage), restartedProcess.receiveMessages("Анна"));
            assertTrue(restartedProcess.receiveMessages("Борис").isEmpty());
        }
    }

    @Test
    void abruptShutdownDoesNotLoseCommittedData() throws SQLException {
        Path database = temporaryDirectory.resolve("abrupt-restart");
        GroupChat interruptedProcess = new PersistentGroupChat(database, CLOCK);
        interruptedProcess.connect("Анна");
        interruptedProcess.sendMessage("Анна", "Сообщение перед сбоем");

        simulateAbruptShutdown(database);

        try (GroupChat restartedProcess = new PersistentGroupChat(database, CLOCK)) {
            assertTrue(restartedProcess.getUserState("Анна").online());
            assertEquals("Сообщение перед сбоем",
                    restartedProcess.getHistory("Анна").get(0).text());
            assertEquals(1, restartedProcess.receiveMessages("Анна").size());
        }
    }

    @Test
    void concurrentMessagesArePersistedWithoutLoss() throws InterruptedException {
        Path database = temporaryDirectory.resolve("concurrent");
        int senderCount = 4;
        int messagesPerSender = 25;

        try (GroupChat chat = new PersistentGroupChat(database, CLOCK)) {
            chat.connect("Получатель");
            IntStream.range(0, senderCount).forEach(i -> chat.connect("Пользователь-" + i));

            ExecutorService executor = Executors.newFixedThreadPool(senderCount);
            CountDownLatch start = new CountDownLatch(1);
            try {
                for (int sender = 0; sender < senderCount; sender++) {
                    String name = "Пользователь-" + sender;
                    executor.submit(() -> {
                        try {
                            start.await();
                            for (int message = 0; message < messagesPerSender; message++) {
                                chat.sendMessage(name, "Сообщение " + message);
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                start.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }

            int expectedCount = senderCount * messagesPerSender;
            assertEquals(expectedCount, chat.getHistory("Получатель").size());
            assertEquals(expectedCount, chat.receiveMessages("Получатель").size());
        }
    }

    private static void simulateAbruptShutdown(Path database) throws SQLException {
        String url = "jdbc:h2:file:" + database.toAbsolutePath().normalize()
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        try (Connection crashConnection = DriverManager.getConnection(url, "sa", "");
             Statement statement = crashConnection.createStatement()) {
            statement.execute("SHUTDOWN IMMEDIATELY");
        }
    }
}
