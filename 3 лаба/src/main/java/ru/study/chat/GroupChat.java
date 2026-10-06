package ru.study.chat;

import java.util.List;

/**
 * Интерфейс группового чата, доступный конечному пользователю программы.
 * Все методы реализации должны быть безопасны для одновременного вызова
 * из разных потоков.
 */
public interface GroupChat extends AutoCloseable {

    void connect(String userName);

    void disconnect(String userName);

    void setNotificationsEnabled(String userName, boolean enabled);

    UserState getUserState(String userName);

    ChatMessage sendMessage(String userName, String text);

    /**
     * Возвращает и удаляет из входящей очереди доставленные пользователю сообщения.
     */
    List<ChatMessage> receiveMessages(String userName);

    List<ChatMessage> getHistory(String userName);

    /** Освобождает ресурсы хранилища. Реализации без внешних ресурсов могут ничего не делать. */
    @Override
    default void close() {
    }
}
