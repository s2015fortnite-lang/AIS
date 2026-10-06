package ru.study.chat;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Запуск: server [port] [database] [bind-address]. */
public final class ServerMain {
    private ServerMain() { }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;
        Path database = Path.of(args.length > 1 ? args[1] : "data/group-chat");
        InetAddress address = InetAddress.getByName(args.length > 2 ? args[2] : "127.0.0.1");
        CountDownLatch stopped = new CountDownLatch(1);
        try (PersistentGroupChat chat = new PersistentGroupChat(database);
             TcpGroupChatServer server = new TcpGroupChatServer(chat, address, port)) {
            Thread shutdown = new Thread(() -> {
                try { server.close(); } finally {
                    try { chat.close(); } finally { stopped.countDown(); }
                }
            }, "chat-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            System.out.println("TCP-сервер: " + address.getHostAddress() + ":" + server.port());
            System.out.println("База H2: " + database.toAbsolutePath());
            System.out.println("Для остановки нажмите Ctrl+C.");
            try { stopped.await(); }
            finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException ignored) { /* JVM уже завершает работу. */ }
            }
        }
    }
}
