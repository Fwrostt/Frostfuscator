package dev.frost.obfuscator.gui;

import dev.frost.obfuscator.gui.logging.DiagnosticLogFiles;
import dev.frost.obfuscator.gui.state.AppDataPaths;

import javax.swing.JOptionPane;
import java.nio.file.Path;

public final class GuiMain {

    private GuiMain() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Path logPath = writeCrashLog(throwable, thread);
            System.err.println("Unhandled exception on " + thread.getName() + ". Crash log: " + logPath);
        });
        try {
            FrostFxApp.launchApp(args);
        } catch (Throwable throwable) {
            Path logPath = writeCrashLog(throwable, Thread.currentThread());
            showFailureDialog(throwable, logPath);
            System.exit(1);
        }
    }

    private static Path writeCrashLog(Throwable throwable, Thread thread) {
        try {
            return DiagnosticLogFiles.writeCrash(throwable, thread);
        } catch (Exception primaryFailure) {
            try {
                Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir"),
                        AppDataPaths.DIRECTORY_NAME + "-crash-fallback");
                return DiagnosticLogFiles.writeCrash(new AppDataPaths(temporaryRoot), throwable, thread);
            } catch (Exception ignored) {
                return Path.of("Crash log could not be written");
            }
        }
    }

    private static void showFailureDialog(Throwable throwable, Path logPath) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String guidance = Runtime.version().feature() < 21
                ? "Open the launcher with Java 21 or newer."
                : "This Java version is supported. See the root cause and crash log below.";
        String message = "Frostfuscator GUI could not start.\n\n"
                + "Java: " + System.getProperty("java.version") + "\n"
                + "Java home: " + System.getProperty("java.home") + "\n\n"
                + guidance + "\n"
                + "Crash log:\n" + logPath + "\n\n"
                + rootCause.getClass().getSimpleName() + ": " + rootCause.getMessage();
        try {
            JOptionPane.showMessageDialog(null, message, "Frostfuscator GUI", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            System.err.println(message);
        }
    }
}
