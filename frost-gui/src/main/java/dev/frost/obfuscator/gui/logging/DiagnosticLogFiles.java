package dev.frost.obfuscator.gui.logging;

import dev.frost.obfuscator.gui.console.LogEntry;
import dev.frost.obfuscator.gui.state.AppDataPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Creates collision-safe, human-readable diagnostic logs under the app-data root. */
public final class DiagnosticLogFiles {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private DiagnosticLogFiles() {
    }

    public static Path writeCrash(Throwable throwable, Thread thread) throws IOException {
        return writeCrash(AppDataPaths.configured(), throwable, thread);
    }

    public static Path writeCrash(AppDataPaths paths, Throwable throwable, Thread thread) throws IOException {
        Path log = createUnique(paths.crashLogsDirectory(), "frostfuscator-crash", ".log");
        try (BufferedWriter output = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE)) {
            output.write("Frostfuscator GUI crash");
            output.newLine();
            output.write("Time: " + LocalDateTime.now());
            output.newLine();
            output.write("Thread: " + (thread == null ? "unknown" : thread.getName()));
            output.newLine();
            output.write("Java: " + System.getProperty("java.version"));
            output.newLine();
            output.write("Java home: " + System.getProperty("java.home"));
            output.newLine();
            output.write("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
            output.newLine();
            output.newLine();
            try (PrintWriter writer = new PrintWriter(output)) {
                throwable.printStackTrace(writer);
            }
        }
        return log;
    }

    public static Session startBuild(AppDataPaths paths) throws IOException {
        Path log = createUnique(paths.buildLogsDirectory(), "frostfuscator-build", ".log");
        return new Session(log);
    }

    static Path createUnique(Path directory, String prefix, String suffix) throws IOException {
        Files.createDirectories(directory);
        String timestamp = FILE_TIME.format(LocalDateTime.now());
        for (int attempt = 0; attempt < 1_000; attempt++) {
            String collision = attempt == 0 ? "" : "-" + attempt;
            Path candidate = directory.resolve(prefix + "-" + timestamp + collision + suffix);
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // Multiple failures/builds in the same millisecond still get separate files.
            }
        }
        return Files.createTempFile(directory, prefix + "-" + timestamp + "-", suffix);
    }

    public static final class Session implements AutoCloseable {
        private final Path path;
        private final BufferedWriter output;
        private int pendingLines;
        private boolean closed;

        private Session(Path path) throws IOException {
            this.path = path;
            this.output = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE);
        }

        public Path path() {
            return path;
        }

        public synchronized void append(LogEntry entry) {
            if (closed || entry == null) return;
            try {
                output.write("[" + LINE_TIME.format(entry.timestamp()) + "] [" + entry.level() + "] ");
                if (entry.transformer() != null && !entry.transformer().isBlank()) {
                    output.write("[" + entry.transformer() + "] ");
                }
                output.write(entry.message() == null ? "" : entry.message());
                output.newLine();
                pendingLines++;
                if (pendingLines >= 32 || entry.level() == LogEntry.Level.ERROR) {
                    output.flush();
                    pendingLines = 0;
                }
            } catch (IOException ignored) {
                // Logging failure must never abort an obfuscation build.
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                output.close();
            } catch (IOException ignored) {
            }
        }
    }
}
