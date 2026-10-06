package ru.study.chat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** UTF-8, поля через TAB, кадры через NUL. Экранирование сохраняет любой текст. */
final class TcpProtocol {
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private TcpProtocol() { }

    static List<String> read(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != 0) {
            if (b == -1) {
                if (buffer.size() == 0) return null;
                throw new EOFException("Незавершённый TCP-кадр");
            }
            if (buffer.size() >= MAX_FRAME_BYTES) throw new IOException("Кадр слишком большой");
            buffer.write(b);
        }
        String frame = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
        List<String> fields = new ArrayList<>();
        for (String field : frame.split("\t", -1)) fields.add(unescape(field));
        return fields;
    }

    static void write(OutputStream output, List<String> fields) throws IOException {
        StringJoiner frame = new StringJoiner("\t");
        for (String field : fields) frame.add(escape(field));
        byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME_BYTES) throw new IOException("Кадр слишком большой");
        output.write(bytes);
        output.write(0);
        output.flush();
    }

    private static String escape(String text) {
        return Objects.requireNonNull(text).replace("\\", "\\\\")
                .replace("\0", "\\0").replace("\t", "\\t")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String text) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (++i == text.length()) throw new IOException("Незавершённое экранирование");
                c = switch (text.charAt(i)) {
                    case '\\' -> '\\'; case '0' -> '\0'; case 't' -> '\t';
                    case 'n' -> '\n'; case 'r' -> '\r';
                    default -> throw new IOException("Неизвестное экранирование");
                };
            }
            result.append(c);
        }
        return result.toString();
    }

    static boolean bool(String field) {
        if (!field.equals("true") && !field.equals("false"))
            throw new IllegalArgumentException("Ожидалось true или false");
        return Boolean.parseBoolean(field);
    }

    static void addMessage(List<String> fields, ChatMessage message) {
        fields.add(Long.toString(message.id()));
        fields.add(message.sender());
        fields.add(message.text());
        fields.add(message.sentAt().toString());
    }

    static ChatMessage message(List<String> fields, int offset) {
        return new ChatMessage(Long.parseLong(fields.get(offset)), fields.get(offset + 1),
                fields.get(offset + 2), Instant.parse(fields.get(offset + 3)));
    }
}
