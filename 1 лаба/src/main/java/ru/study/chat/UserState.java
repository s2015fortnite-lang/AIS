package ru.study.chat;

import java.time.Instant;
import java.util.Objects;

/** Снимок текущего состояния пользователя. */
public record UserState(
        String userName,
        boolean online,
        Instant lastConnectedAt,
        boolean notificationsEnabled
) {
    public UserState {
        Objects.requireNonNull(userName, "Имя пользователя не может быть null");
        Objects.requireNonNull(lastConnectedAt, "Время последнего подключения не может быть null");
    }
}
