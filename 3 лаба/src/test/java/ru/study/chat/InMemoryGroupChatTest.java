package ru.study.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryGroupChatTest {

    private static final Instant NOW = Instant.parse("2026-09-22T09:00:00Z");

    private GroupChat chat;

    @BeforeEach
    void setUp() {
        chat = new InMemoryGroupChat(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void mainScenarioDeliversMessageOnlyToOnlineUsersWithNotifications() {
        chat.connect("Анна");
        chat.connect("Борис");
        chat.connect("Виктор");
        chat.setNotificationsEnabled("Виктор", false);

        ChatMessage message = chat.sendMessage("Анна", "Всем привет!");

        assertEquals(List.of(message), chat.receiveMessages("Анна"));
        assertEquals(List.of(message), chat.receiveMessages("Борис"));
        assertTrue(chat.receiveMessages("Виктор").isEmpty());
        assertEquals(List.of(message), chat.getHistory("Виктор"));
    }

    @Test
    void disconnectedUserDoesNotReceiveMessages() {
        chat.connect("Анна");
        chat.connect("Борис");
        chat.disconnect("Борис");

        chat.sendMessage("Анна", "Сообщение");

        assertTrue(chat.receiveMessages("Борис").isEmpty());
        assertFalse(chat.getUserState("Борис").online());
    }

    @Test
    void userStateContainsConnectionTimeAndNotificationSetting() {
        chat.connect("Анна");
        chat.setNotificationsEnabled("Анна", false);

        UserState state = chat.getUserState("Анна");

        assertTrue(state.online());
        assertEquals(NOW, state.lastConnectedAt());
        assertFalse(state.notificationsEnabled());
    }

    @Test
    void disconnectedUserCannotSendMessage() {
        chat.connect("Анна");
        chat.disconnect("Анна");

        assertThrows(IllegalStateException.class,
                () -> chat.sendMessage("Анна", "Не должно отправиться"));
    }

    @Test
    void unknownUserAndEmptyInputAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> chat.getHistory("Неизвестный"));
        assertThrows(IllegalArgumentException.class, () -> chat.connect("  "));

        chat.connect("Анна");
        assertThrows(IllegalArgumentException.class, () -> chat.sendMessage("Анна", ""));
    }

    @Test
    void concurrentMessagesAreNotLost() throws InterruptedException {
        int senderCount = 8;
        int messagesPerSender = 50;
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
        List<ChatMessage> received = chat.receiveMessages("Получатель");
        assertEquals(expectedCount, received.size());
        assertEquals(expectedCount, chat.getHistory("Получатель").size());
        assertEquals(expectedCount,
                received.stream().map(ChatMessage::id).distinct().count());
    }
}
