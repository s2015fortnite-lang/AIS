package ru.study.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Потокобезопасный групповой чат, хранящий пользователей, историю и входящие
 * сообщения только в оперативной памяти.
 */
public final class InMemoryGroupChat implements GroupChat {

    private final Clock clock;
    private final Map<String, UserData> users = new HashMap<>();
    private final List<ChatMessage> history = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long nextMessageId = 1;

    public InMemoryGroupChat() {
        this(Clock.systemUTC());
    }

    /** Конструктор с часами нужен для детерминированных тестов. */
    public InMemoryGroupChat(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Часы не могут быть null");
    }

    @Override
    public void connect(String userName) {
        String normalizedName = validateUserName(userName);
        lock.writeLock().lock();
        try {
            UserData user = users.computeIfAbsent(normalizedName, ignored -> new UserData());
            user.online = true;
            user.lastConnectedAt = clock.instant();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void disconnect(String userName) {
        lock.writeLock().lock();
        try {
            requireUser(userName).online = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void setNotificationsEnabled(String userName, boolean enabled) {
        lock.writeLock().lock();
        try {
            requireUser(userName).notificationsEnabled = enabled;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public UserState getUserState(String userName) {
        lock.readLock().lock();
        try {
            UserData user = requireUser(userName);
            return new UserState(validateUserName(userName), user.online,
                    user.lastConnectedAt, user.notificationsEnabled);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ChatMessage sendMessage(String userName, String text) {
        String normalizedName = validateUserName(userName);
        String validatedText = validateMessageText(text);

        lock.writeLock().lock();
        try {
            UserData sender = requireUser(normalizedName);
            if (!sender.online) {
                throw new IllegalStateException("Отключенный пользователь не может отправлять сообщения");
            }

            ChatMessage message = new ChatMessage(
                    nextMessageId++, normalizedName, validatedText, clock.instant());
            history.add(message);

            for (UserData recipient : users.values()) {
                if (recipient.online && recipient.notificationsEnabled) {
                    recipient.inbox.add(message);
                }
            }
            return message;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<ChatMessage> receiveMessages(String userName) {
        lock.writeLock().lock();
        try {
            UserData user = requireUser(userName);
            List<ChatMessage> messages = List.copyOf(user.inbox);
            user.inbox.clear();
            return messages;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<ChatMessage> getHistory(String userName) {
        lock.readLock().lock();
        try {
            requireUser(userName);
            return List.copyOf(history);
        } finally {
            lock.readLock().unlock();
        }
    }

    private UserData requireUser(String userName) {
        String normalizedName = validateUserName(userName);
        UserData user = users.get(normalizedName);
        if (user == null) {
            throw new IllegalArgumentException("Пользователь не найден: " + normalizedName);
        }
        return user;
    }

    private static String validateUserName(String userName) {
        Objects.requireNonNull(userName, "Имя пользователя не может быть null");
        if (userName.isBlank()) {
            throw new IllegalArgumentException("Имя пользователя не может быть пустым");
        }
        return userName;
    }

    private static String validateMessageText(String text) {
        Objects.requireNonNull(text, "Текст сообщения не может быть null");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Текст сообщения не может быть пустым");
        }
        return text;
    }

    private static final class UserData {
        private boolean online;
        private boolean notificationsEnabled = true;
        private Instant lastConnectedAt;
        private final Queue<ChatMessage> inbox = new ArrayDeque<>();
    }
}
