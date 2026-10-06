package ru.study.chat;

import java.time.Instant;
import java.util.Objects;

/** Неизменяемое сообщение чата. */
public record ChatMessage(long id, String sender, String text, Instant sentAt) {

    public ChatMessage {
        if (id < 1) {
            throw new IllegalArgumentException("Идентификатор сообщения должен быть положительным");
        }
        sender = requireText(sender, "Имя отправителя");
        text = requireText(text, "Текст сообщения");
        Objects.requireNonNull(sentAt, "Время отправки не может быть null");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " не может быть null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " не может быть пустым");
        }
        return value;
    }
}
