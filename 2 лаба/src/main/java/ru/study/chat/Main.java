package ru.study.chat;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;

/** Консольная форма для ручной проверки возможностей лабораторной работы. */
public final class Main {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final GroupChat chat;
    private final Scanner scanner;

    private Main(GroupChat chat, Scanner scanner) {
        this.chat = chat;
        this.scanner = scanner;
    }

    public static void main(String[] args) {
        Path databaseFile = args.length == 0
                ? Path.of("data", "group-chat")
                : Path.of(args[0]);

        System.out.println("Групповой чат — лабораторная работа № 2");
        System.out.println("База данных: " + databaseFile.toAbsolutePath().normalize());
        System.out.println("Данные сохраняются после завершения программы.");
        try (PersistentGroupChat chat = new PersistentGroupChat(databaseFile)) {
            new Main(chat, new Scanner(System.in)).run();
        } catch (ChatStorageException exception) {
            System.err.println("Ошибка хранилища: " + exception.getMessage());
        }
    }

    private void run() {
        boolean running = true;
        while (running) {
            printMenu();
            String command = read("Выберите действие: ");
            try {
                running = execute(command);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                System.out.println("Ошибка: " + exception.getMessage());
            }
            System.out.println();
        }
        System.out.println("Работа чата завершена.");
    }

    private boolean execute(String command) {
        switch (command) {
            case "1" -> connectUser();
            case "2" -> disconnectUser();
            case "3" -> changeNotifications();
            case "4" -> sendMessage();
            case "5" -> receiveMessages();
            case "6" -> showHistory();
            case "7" -> showUserState();
            case "0" -> {
                return false;
            }
            default -> System.out.println("Неизвестная команда. Введите число от 0 до 7.");
        }
        return true;
    }

    private void connectUser() {
        String userName = readUserName();
        chat.connect(userName);
        System.out.println("Пользователь «" + userName + "» подключен.");
    }

    private void disconnectUser() {
        String userName = readUserName();
        chat.disconnect(userName);
        System.out.println("Пользователь «" + userName + "» отключен.");
    }

    private void changeNotifications() {
        String userName = readUserName();
        String value = read("Включить уведомления? (да/нет): ").toLowerCase();
        boolean enabled = switch (value) {
            case "да", "д", "yes", "y" -> true;
            case "нет", "н", "no", "n" -> false;
            default -> throw new IllegalArgumentException("Введите «да» или «нет»");
        };
        chat.setNotificationsEnabled(userName, enabled);
        System.out.println("Уведомления " + (enabled ? "включены." : "выключены."));
    }

    private void sendMessage() {
        String userName = read("Имя отправителя: ");
        String text = read("Текст сообщения: ");
        ChatMessage message = chat.sendMessage(userName, text);
        System.out.println("Сообщение отправлено, номер: " + message.id());
    }

    private void receiveMessages() {
        String userName = readUserName();
        List<ChatMessage> messages = chat.receiveMessages(userName);
        if (messages.isEmpty()) {
            System.out.println("Новых доставленных сообщений нет.");
            return;
        }
        System.out.println("Доставленные сообщения:");
        messages.forEach(this::printMessage);
        System.out.println("После просмотра входящая очередь очищена.");
    }

    private void showHistory() {
        String userName = readUserName();
        List<ChatMessage> messages = chat.getHistory(userName);
        if (messages.isEmpty()) {
            System.out.println("История чата пуста.");
            return;
        }
        System.out.println("История чата:");
        messages.forEach(this::printMessage);
    }

    private void showUserState() {
        UserState state = chat.getUserState(readUserName());
        System.out.println("Пользователь: " + state.userName());
        System.out.println("Состояние: " + (state.online() ? "в сети" : "не в сети"));
        System.out.println("Последнее подключение: " + TIME_FORMAT.format(state.lastConnectedAt()));
        System.out.println("Уведомления: " + (state.notificationsEnabled() ? "включены" : "выключены"));
    }

    private void printMessage(ChatMessage message) {
        System.out.printf("  #%d [%s] %s: %s%n",
                message.id(), TIME_FORMAT.format(message.sentAt()),
                message.sender(), message.text());
    }

    private String readUserName() {
        return read("Имя пользователя: ");
    }

    private String read(String prompt) {
        System.out.print(prompt);
        if (!scanner.hasNextLine()) {
            return "0";
        }
        return scanner.nextLine();
    }

    private static void printMenu() {
        System.out.println("----------------------------------------");
        System.out.println("1. Подключить пользователя");
        System.out.println("2. Отключить пользователя");
        System.out.println("3. Включить или выключить уведомления");
        System.out.println("4. Отправить сообщение");
        System.out.println("5. Получить доставленные сообщения");
        System.out.println("6. Посмотреть историю чата");
        System.out.println("7. Посмотреть состояние пользователя");
        System.out.println("0. Завершить работу");
    }
}
