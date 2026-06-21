package dev.frost.obfuscator.gui;

import javax.swing.JOptionPane;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

public final class GuiMain {

    private GuiMain() {
    }

    public static void main(String[] args) {
        try {
            FrostFxApp.launchApp(args);
        } catch (Throwable throwable) {
            Path logPath = writeCrashLog(throwable);
            showFailureDialog(throwable, logPath);
            System.exit(1);
        }
    }

    private static Path writeCrashLog(Throwable throwable) {
        Path logPath = Path.of(System.getProperty("user.home"), ".frostfuscator", "gui-crash.log");
        try {
            Files.createDirectories(logPath.getParent());
            StringWriter stackTrace = new StringWriter();
            try (PrintWriter writer = new PrintWriter(stackTrace)) {
                writer.println("Frostfuscator GUI failed to start");
                writer.println("Time: " + LocalDateTime.now());
                writer.println("Java: " + System.getProperty("java.version"));
                writer.println("Java home: " + System.getProperty("java.home"));
                writer.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
                writer.println();
                throwable.printStackTrace(writer);
            }
            Files.writeString(logPath, stackTrace.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return Path.of("gui-crash.log");
        }
        return logPath;
    }

    private static void showFailureDialog(Throwable throwable, Path logPath) {
        String message = "Frostfuscator GUI could not start.\n\n"
                + "Java: " + System.getProperty("java.version") + "\n"
                + "Java home: " + System.getProperty("java.home") + "\n\n"
                + "Open the launcher with Java 21 or newer.\n"
                + "Crash log:\n" + logPath + "\n\n"
                + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        try {
            JOptionPane.showMessageDialog(null, message, "Frostfuscator GUI", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            System.err.println(message);
        }
    }
}
