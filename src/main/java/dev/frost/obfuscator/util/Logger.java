package dev.frost.obfuscator.util;

import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class Logger {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("Frostfuscator");
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private static final String BANNER = """
            Frostfuscator v1.2.2
            Java obfuscation toolkit
            """;

    private Logger() {
    }

    public static void printBanner() {
        System.out.println(BANNER);
    }

    public static void info(String message, Object... args) {
        String formatted = format(message, args);
        LOGGER.info(formatted);
        publish("INFO", formatted);
    }

    public static void warn(String message, Object... args) {
        String formatted = format(message, args);
        LOGGER.warn(formatted);
        publish("WARN", formatted);
    }

    public static void error(String message, Object... args) {
        String formatted = format(message, args);
        LOGGER.error(formatted);
        publish("ERROR", formatted);
    }

    public static void error(String message, Throwable throwable) {
        LOGGER.error(message, throwable);
        publish("ERROR", message + " - " + throwable.getMessage());
    }

    public static void debug(String message, Object... args) {
        String formatted = format(message, args);
        LOGGER.debug(formatted);
        publish("DEBUG", formatted);
    }

    public static void addListener(Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<String> listener) {
        LISTENERS.remove(listener);
    }

    private static void publish(String level, String message) {
        String line = "[" + level + "] " + message;
        for (Consumer<String> listener : LISTENERS) {
            listener.accept(line);
        }
    }

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) return message;
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int i = 0;
        while (i < message.length()) {
            if (i + 1 < message.length() && message.charAt(i) == '{' && message.charAt(i + 1) == '}' && argIdx < args.length) {
                sb.append(args[argIdx++]);
                i += 2;
            } else {
                sb.append(message.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }
}
