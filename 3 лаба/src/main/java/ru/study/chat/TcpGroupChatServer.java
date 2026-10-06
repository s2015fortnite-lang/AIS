package ru.study.chat;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Один обработчик на TCP-соединение; хранилище из лабораторной № 2 общее. */
public final class TcpGroupChatServer implements AutoCloseable {
    private final GroupChat chat;
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean closed;

    /** Сервер не владеет chat: вызывающий код закрывает хранилище после сервера. */
    public TcpGroupChatServer(GroupChat chat, InetAddress address, int port) throws IOException {
        this.chat = Objects.requireNonNull(chat);
        listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(address, port)); }
        catch (IOException e) { listener.close(); throw e; }
        acceptor = new Thread(this::accept, "chat-tcp-accept");
        acceptor.start();
    }

    public int port() { return listener.getLocalPort(); }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                clients.add(socket);
                if (closed) { socket.close(); clients.remove(socket); break; }
                workers.execute(() -> serve(socket));
            } catch (IOException e) {
                if (!closed) System.err.println("Ошибка TCP-сервера: " + e.getMessage());
                break;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            List<String> request;
            while ((request = TcpProtocol.read(input)) != null) {
                TcpProtocol.write(output, dispatch(request));
            }
        } catch (IOException e) {
            // EOF, обрыв связи и некорректный кадр завершают только это соединение.
        } finally { clients.remove(socket); }
    }

    private List<String> dispatch(List<String> request) {
        try {
            String command = request.get(0);
            int expected = switch (command) {
                case "CONNECT", "DISCONNECT", "STATE", "RECEIVE", "HISTORY" -> 2;
                case "NOTIFY", "SEND" -> 3;
                default -> throw new IllegalArgumentException("Неизвестная команда: " + command);
            };
            if (request.size() != expected) throw new IllegalArgumentException("Неверное число полей");
            String name = request.get(1);
            List<String> response = new ArrayList<>(List.of("OK"));
            switch (command) {
                case "CONNECT" -> chat.connect(name);
                case "DISCONNECT" -> chat.disconnect(name);
                case "NOTIFY" -> chat.setNotificationsEnabled(name, TcpProtocol.bool(request.get(2)));
                case "STATE" -> {
                    UserState state = chat.getUserState(name);
                    response.addAll(List.of(state.userName(), Boolean.toString(state.online()),
                            state.lastConnectedAt().toString(), Boolean.toString(state.notificationsEnabled())));
                }
                case "SEND" -> TcpProtocol.addMessage(response, chat.sendMessage(name, request.get(2)));
                case "RECEIVE", "HISTORY" -> {
                    List<ChatMessage> messages = command.equals("RECEIVE")
                            ? chat.receiveMessages(name) : chat.getHistory(name);
                    response.add(Integer.toString(messages.size()));
                    messages.forEach(message -> TcpProtocol.addMessage(response, message));
                }
                default -> throw new IllegalArgumentException("Неизвестная команда");
            }
            return response;
        } catch (IllegalArgumentException | NullPointerException e) {
            return List.of("ERR", "ARGUMENT", String.valueOf(e.getMessage()));
        } catch (IllegalStateException e) {
            return List.of("ERR", "STATE", String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            return List.of("ERR", "STORAGE", "Ошибка хранилища сервера");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        try { acceptor.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        clients.forEach(socket -> { try { socket.close(); } catch (IOException ignored) { } });
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
