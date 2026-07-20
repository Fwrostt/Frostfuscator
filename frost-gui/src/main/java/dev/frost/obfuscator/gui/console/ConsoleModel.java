package dev.frost.obfuscator.gui.console;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConsoleModel {
    private static final int MAX_ENTRIES = 10_000;
    private static final Pattern TRANSFORMER = Pattern.compile("\\[([^\\]]+)]");
    private static final Pattern REFERENCE = Pattern.compile(
            "(?<![\\w.$/])((?:[A-Za-z_$][\\w$]*[./])+[A-Z_$][\\w$]*(?:#[\\w$<>]+)?(?::\\d+)?)");
    private final ObservableList<LogEntry> entries = FXCollections.observableArrayList();
    private final ConcurrentLinkedQueue<LogEntry> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    public ObservableList<LogEntry> entries() { return entries; }

    public void restore(List<LogEntry> restored) {
        List<LogEntry> safe = restored == null ? List.of() : restored;
        Runnable apply = () -> {
            int start = Math.max(0, safe.size() - MAX_ENTRIES);
            entries.setAll(safe.subList(start, safe.size()));
        };
        if (Platform.isFxApplicationThread()) apply.run();
        else Platform.runLater(apply);
    }

    public void clear() {
        pending.clear();
        if (Platform.isFxApplicationThread()) {
            entries.clear();
        } else {
            Platform.runLater(() -> {
                pending.clear();
                entries.clear();
            });
        }
    }

    public void append(String line) {
        enqueue(parse(line));
    }

    public void append(LogEntry.Level level, String message) {
        String safeMessage = message == null ? "" : message;
        enqueue(new LogEntry(LocalDateTime.now(), level, "", safeMessage, findReference(safeMessage)));
    }

    private void enqueue(LogEntry entry) {
        if (Platform.isFxApplicationThread()) {
            entries.add(entry);
            return;
        }
        pending.add(entry);
        if (flushScheduled.compareAndSet(false, true)) Platform.runLater(this::flush);
    }

    private void flush() {
        List<LogEntry> batch = new ArrayList<>();
        LogEntry entry;
        while ((entry = pending.poll()) != null) batch.add(entry);
        if (!batch.isEmpty()) {
            int overflow = entries.size() + batch.size() - MAX_ENTRIES;
            if (overflow > 0) entries.remove(0, Math.min(overflow, entries.size()));
            entries.addAll(batch);
        }
        flushScheduled.set(false);
        if (!pending.isEmpty() && flushScheduled.compareAndSet(false, true)) Platform.runLater(this::flush);
    }

    static LogEntry parse(String line) {
        if (line == null) line = "";
        String lower = line.toLowerCase(Locale.ROOT);
        LogEntry.Level level = lower.contains("error") || lower.contains("failed") ? LogEntry.Level.ERROR
                : lower.contains("warn") ? LogEntry.Level.WARNING
                : lower.contains("debug") ? LogEntry.Level.DEBUG
                : lower.contains("complete") || lower.contains("success") ? LogEntry.Level.SUCCESS
                : LogEntry.Level.INFO;
        Matcher matcher = TRANSFORMER.matcher(line);
        String transformer = "";
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!candidate.equalsIgnoreCase("DEBUG")
                    && !candidate.equalsIgnoreCase("INFO")
                    && !candidate.equalsIgnoreCase("WARN")
                    && !candidate.equalsIgnoreCase("WARNING")
                    && !candidate.equalsIgnoreCase("ERROR")
                    && !candidate.equalsIgnoreCase("SUCCESS")) {
                transformer = candidate;
                break;
            }
        }
        String displayMessage = line.replaceFirst(
                "^\\[(?:DEBUG|INFO|WARN|WARNING|ERROR|SUCCESS)]\\s*", "");
        return new LogEntry(LocalDateTime.now(), level, transformer, displayMessage, findReference(displayMessage));
    }

    static String findReference(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = REFERENCE.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }
}
