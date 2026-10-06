package ru.study.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Потокобезопасный групповой чат с постоянным файловым хранилищем H2.
 * Каждая операция выполняется в отдельной транзакции.
 */
public final class PersistentGroupChat implements GroupChat {

    private final Clock clock;
    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean closed;

    public PersistentGroupChat(Path databaseFile) {
        this(databaseFile, Clock.systemUTC());
    }

    /** Конструктор с часами нужен для детерминированных тестов. */
    public PersistentGroupChat(Path databaseFile, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Часы не могут быть null");
        Path normalizedFile = prepareDatabasePath(databaseFile);
        String url = "jdbc:h2:file:" + normalizedFile + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        try {
            connection = DriverManager.getConnection(url, "sa", "");
            connection.setAutoCommit(false);
            createSchema();
            connection.commit();
        } catch (SQLException exception) {
            throw new ChatStorageException("Не удалось открыть базу данных", exception);
        }
    }

    @Override
    public void connect(String userName) {
        String name = validateUserName(userName);
        inTransaction(() -> {
            Instant connectedAt = clock.instant();
            if (userExists(name)) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE users SET online = TRUE, last_connected = ? WHERE name = ?
                        """)) {
                    statement.setLong(1, connectedAt.toEpochMilli());
                    statement.setString(2, name);
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO users(name, online, last_connected, notifications_enabled)
                        VALUES (?, TRUE, ?, TRUE)
                        """)) {
                    statement.setString(1, name);
                    statement.setLong(2, connectedAt.toEpochMilli());
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    @Override
    public void disconnect(String userName) {
        String name = validateUserName(userName);
        inTransaction(() -> {
            requireUser(name);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET online = FALSE WHERE name = ?")) {
                statement.setString(1, name);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void setNotificationsEnabled(String userName, boolean enabled) {
        String name = validateUserName(userName);
        inTransaction(() -> {
            requireUser(name);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET notifications_enabled = ? WHERE name = ?")) {
                statement.setBoolean(1, enabled);
                statement.setString(2, name);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public UserState getUserState(String userName) {
        String name = validateUserName(userName);
        return inTransaction(() -> requireUser(name));
    }

    @Override
    public ChatMessage sendMessage(String userName, String text) {
        String name = validateUserName(userName);
        String messageText = validateMessageText(text);
        return inTransaction(() -> {
            UserState sender = requireUser(name);
            if (!sender.online()) {
                throw new IllegalStateException("Отключенный пользователь не может отправлять сообщения");
            }

            Instant sentAt = clock.instant();
            long messageId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO messages(sender, text, sent_at) VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, messageText);
                statement.setLong(3, sentAt.toEpochMilli());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("База данных не вернула номер сообщения");
                    }
                    messageId = keys.getLong(1);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO deliveries(user_name, message_id)
                    SELECT name, ? FROM users
                    WHERE online = TRUE AND notifications_enabled = TRUE
                    """)) {
                statement.setLong(1, messageId);
                statement.executeUpdate();
            }
            return new ChatMessage(messageId, name, messageText, sentAt);
        });
    }

    @Override
    public List<ChatMessage> receiveMessages(String userName) {
        String name = validateUserName(userName);
        return inTransaction(() -> {
            requireUser(name);
            List<ChatMessage> messages = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT m.id, m.sender, m.text, m.sent_at
                    FROM deliveries d
                    JOIN messages m ON m.id = d.message_id
                    WHERE d.user_name = ?
                    ORDER BY m.id
                    """)) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        messages.add(readMessage(result));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM deliveries WHERE user_name = ?")) {
                statement.setString(1, name);
                statement.executeUpdate();
            }
            return List.copyOf(messages);
        });
    }

    @Override
    public List<ChatMessage> getHistory(String userName) {
        String name = validateUserName(userName);
        return inTransaction(() -> {
            requireUser(name);
            List<ChatMessage> messages = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, sender, text, sent_at FROM messages ORDER BY id
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    messages.add(readMessage(result));
                }
            }
            return List.copyOf(messages);
        });
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                connection.close();
                closed = true;
            }
        } catch (SQLException exception) {
            throw new ChatStorageException("Не удалось закрыть базу данных", exception);
        } finally {
            lock.unlock();
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        name VARCHAR(255) PRIMARY KEY,
                        online BOOLEAN NOT NULL,
                        last_connected BIGINT NOT NULL,
                        notifications_enabled BOOLEAN NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        sender VARCHAR(255) NOT NULL REFERENCES users(name),
                        text VARCHAR(10000) NOT NULL,
                        sent_at BIGINT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS deliveries (
                        user_name VARCHAR(255) NOT NULL REFERENCES users(name),
                        message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                        PRIMARY KEY (user_name, message_id)
                    )
                    """);
        }
    }

    private boolean userExists(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM users WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private UserState requireUser(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT online, last_connected, notifications_enabled
                FROM users WHERE name = ?
                """)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Пользователь не найден: " + name);
                }
                return new UserState(name, result.getBoolean("online"),
                        Instant.ofEpochMilli(result.getLong("last_connected")),
                        result.getBoolean("notifications_enabled"));
            }
        }
    }

    private static ChatMessage readMessage(ResultSet result) throws SQLException {
        return new ChatMessage(result.getLong("id"), result.getString("sender"),
                result.getString("text"),
                Instant.ofEpochMilli(result.getLong("sent_at")));
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        lock.lock();
        try {
            ensureOpen();
            T result = operation.execute();
            connection.commit();
            return result;
        } catch (SQLException exception) {
            rollback(exception);
            throw new ChatStorageException("Ошибка работы с базой данных", exception);
        } catch (RuntimeException exception) {
            rollback(exception);
            throw exception;
        } finally {
            lock.unlock();
        }
    }

    private void rollback(Exception originalException) {
        try {
            if (!connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Хранилище чата уже закрыто");
        }
    }

    private static Path prepareDatabasePath(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "Путь к базе данных не может быть null");
        Path normalized = databaseFile.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException exception) {
                throw new ChatStorageException("Не удалось создать каталог базы данных", exception);
            }
        }
        return normalized;
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

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute() throws SQLException;
    }
}
