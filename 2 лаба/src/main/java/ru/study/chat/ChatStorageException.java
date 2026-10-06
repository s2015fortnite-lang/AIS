package ru.study.chat;

/** Ошибка обращения к постоянному хранилищу чата. */
public final class ChatStorageException extends RuntimeException {

    public ChatStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
