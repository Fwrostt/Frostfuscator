package dev.frost.obfuscator.gui.state;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ConfigWriter;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.build.BuildRecord;
import dev.frost.obfuscator.gui.config.ConfigurationBinder;
import dev.frost.obfuscator.gui.console.ConsoleModel;
import dev.frost.obfuscator.gui.console.LogEntry;
import javafx.collections.ListChangeListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Autosaves the working configuration and useful session activity without
 * blocking the JavaFX application thread on disk access.
 */
public final class WorkspacePersistence implements AutoCloseable {
    private static final int MAX_BUILD_RECORDS = 100;
    private static final int MAX_LOG_ENTRIES = 5_000;
    private final AppDataPaths paths;
    private final ProjectState state;
    private final ConfigurationBinder binder;
    private final ConsoleModel console;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "frostfuscator-workspace-writer");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> pendingWorkspace;
    private ScheduledFuture<?> pendingActivity;
    private volatile WorkspaceSnapshot latestWorkspace;
    private volatile ActivitySnapshot latestActivity;
    private boolean started;
    private boolean closed;

    public WorkspacePersistence(AppDataPaths paths, ProjectState state,
                                ConfigurationBinder binder, ConsoleModel console) {
        this.paths = paths;
        this.state = state;
        this.binder = binder;
        this.console = console;
    }

    public void restore() {
        restoreWorkspace();
        restoreActivity();
    }

    public void start() {
        if (started) return;
        started = true;
        state.revisionProperty().addListener((obs, old, value) -> scheduleWorkspaceSave());
        state.profileProperty().addListener((obs, old, value) -> scheduleWorkspaceSave());
        state.goalProperty().addListener((obs, old, value) -> scheduleWorkspaceSave());
        state.outputSizeLimitMbProperty().addListener((obs, old, value) -> scheduleWorkspaceSave());
        state.runtimeOverheadPreferenceProperty().addListener((obs, old, value) -> scheduleWorkspaceSave());
        state.dirtyProperty().addListener((obs, old, value) -> scheduleWorkspaceSave());
        state.buildHistory().addListener((ListChangeListener<BuildRecord>) change -> scheduleActivitySave());
        console.entries().addListener((ListChangeListener<LogEntry>) change -> scheduleActivitySave());
        captureWorkspace();
        captureActivity();
    }

    public void saveNow() {
        captureWorkspace();
        captureActivity();
        cancelPending();
        WorkspaceSnapshot workspace = latestWorkspace;
        ActivitySnapshot activity = latestActivity;
        if (workspace != null) writeWorkspace(workspace);
        if (activity != null) writeActivity(activity);
    }

    private void restoreWorkspace() {
        Properties metadata = loadProperties(paths.sessionMetadata());
        if (Files.isRegularFile(paths.sessionConfig())) {
            try {
                ObfuscationConfig configuration = ConfigLoader.load(paths.sessionConfig());
                ConfigurationBinder.ensureAllTransformers(configuration);
                state.replaceConfiguration(configuration);
            } catch (RuntimeException ignored) {
                // Keep the known-good default configuration when an autosave is damaged.
            }
        }
        state.profileProperty().set(metadata.getProperty("profile", state.profileProperty().get()));
        state.goalProperty().set(metadata.getProperty("goal", state.goalProperty().get()));
        state.outputSizeLimitMbProperty().set(parseDouble(metadata, "outputSizeLimitMb",
                state.outputSizeLimitMbProperty().get()));
        state.runtimeOverheadPreferenceProperty().set(parseDouble(metadata, "runtimeOverheadPreference",
                state.runtimeOverheadPreferenceProperty().get()));
        state.dirtyProperty().set(Boolean.parseBoolean(metadata.getProperty("dirty", "false")));
    }

    private void restoreActivity() {
        Properties history = loadProperties(paths.buildHistory());
        int historyCount = parseInt(history, "count", 0);
        List<BuildRecord> records = new ArrayList<>();
        for (int index = 0; index < Math.min(historyCount, MAX_BUILD_RECORDS); index++) {
            try {
                String prefix = "record." + index + ".";
                String output = decode(history.getProperty(prefix + "output", ""));
                records.add(new BuildRecord(
                        LocalDateTime.parse(history.getProperty(prefix + "time")),
                        BuildRecord.Status.valueOf(history.getProperty(prefix + "status")),
                        output.isBlank() ? null : Path.of(output),
                        Duration.ofMillis(Long.parseLong(history.getProperty(prefix + "durationMillis", "0"))),
                        decode(history.getProperty(prefix + "message", ""))));
            } catch (RuntimeException ignored) {
                // Preserve the remaining valid records.
            }
        }
        state.buildHistory().setAll(records);

        Properties logs = loadProperties(paths.latestLog());
        int logCount = parseInt(logs, "count", 0);
        List<LogEntry> entries = new ArrayList<>();
        for (int index = 0; index < Math.min(logCount, MAX_LOG_ENTRIES); index++) {
            try {
                String prefix = "entry." + index + ".";
                entries.add(new LogEntry(
                        LocalDateTime.parse(logs.getProperty(prefix + "time")),
                        LogEntry.Level.valueOf(logs.getProperty(prefix + "level")),
                        decode(logs.getProperty(prefix + "transformer", "")),
                        decode(logs.getProperty(prefix + "message", "")),
                        decode(logs.getProperty(prefix + "reference", ""))));
            } catch (RuntimeException ignored) {
                // Preserve the remaining valid entries.
            }
        }
        console.restore(entries);
    }

    private void scheduleWorkspaceSave() {
        if (!started || closed) return;
        captureWorkspace();
        if (pendingWorkspace != null) pendingWorkspace.cancel(false);
        pendingWorkspace = writer.schedule(() -> {
            WorkspaceSnapshot snapshot = latestWorkspace;
            if (snapshot != null) writeWorkspace(snapshot);
        }, 420, TimeUnit.MILLISECONDS);
    }

    private void scheduleActivitySave() {
        if (!started || closed) return;
        captureActivity();
        if (pendingActivity != null) pendingActivity.cancel(false);
        pendingActivity = writer.schedule(() -> {
            ActivitySnapshot snapshot = latestActivity;
            if (snapshot != null) writeActivity(snapshot);
        }, 650, TimeUnit.MILLISECONDS);
    }

    private void captureWorkspace() {
        latestWorkspace = new WorkspaceSnapshot(
                binder.snapshot(),
                state.profileProperty().get(),
                state.goalProperty().get(),
                state.outputSizeLimitMbProperty().get(),
                state.runtimeOverheadPreferenceProperty().get(),
                state.dirtyProperty().get());
    }

    private void captureActivity() {
        List<BuildRecord> history = List.copyOf(state.buildHistory().subList(
                0, Math.min(state.buildHistory().size(), MAX_BUILD_RECORDS)));
        List<LogEntry> allLogs = console.entries();
        int start = Math.max(0, allLogs.size() - MAX_LOG_ENTRIES);
        latestActivity = new ActivitySnapshot(history, List.copyOf(allLogs.subList(start, allLogs.size())));
    }

    private void writeWorkspace(WorkspaceSnapshot snapshot) {
        Path config = paths.sessionConfig();
        Path temporary = config.resolveSibling(config.getFileName() + ".tmp");
        try {
            Files.createDirectories(paths.workspaceDirectory());
            ConfigWriter.save(snapshot.configuration(), temporary);
            PreferencesStore.moveAtomically(temporary, config);
        } catch (IOException ignored) {
            deleteQuietly(temporary);
        }

        Properties metadata = new Properties();
        metadata.setProperty("formatVersion", "1");
        metadata.setProperty("profile", snapshot.profile());
        metadata.setProperty("goal", snapshot.goal());
        metadata.setProperty("outputSizeLimitMb", Double.toString(snapshot.outputSizeLimitMb()));
        metadata.setProperty("runtimeOverheadPreference",
                Double.toString(snapshot.runtimeOverheadPreference()));
        metadata.setProperty("dirty", Boolean.toString(snapshot.dirty()));
        storeProperties(metadata, paths.sessionMetadata(), "Frostfuscator workspace session");
    }

    private void writeActivity(ActivitySnapshot snapshot) {
        Properties history = new Properties();
        history.setProperty("formatVersion", "1");
        history.setProperty("count", Integer.toString(snapshot.history().size()));
        for (int index = 0; index < snapshot.history().size(); index++) {
            BuildRecord record = snapshot.history().get(index);
            String prefix = "record." + index + ".";
            history.setProperty(prefix + "time", record.time().toString());
            history.setProperty(prefix + "status", record.status().name());
            history.setProperty(prefix + "output", encode(record.output() == null ? "" : record.output().toString()));
            history.setProperty(prefix + "durationMillis", Long.toString(record.duration().toMillis()));
            history.setProperty(prefix + "message", encode(record.message()));
        }
        storeProperties(history, paths.buildHistory(), "Frostfuscator build history");

        Properties logs = new Properties();
        logs.setProperty("formatVersion", "1");
        logs.setProperty("count", Integer.toString(snapshot.logs().size()));
        for (int index = 0; index < snapshot.logs().size(); index++) {
            LogEntry entry = snapshot.logs().get(index);
            String prefix = "entry." + index + ".";
            logs.setProperty(prefix + "time", entry.timestamp().toString());
            logs.setProperty(prefix + "level", entry.level().name());
            logs.setProperty(prefix + "transformer", encode(entry.transformer()));
            logs.setProperty(prefix + "message", encode(entry.message()));
            logs.setProperty(prefix + "reference", encode(entry.reference()));
        }
        storeProperties(logs, paths.latestLog(), "Frostfuscator latest console session");
    }

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) return properties;
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static void storeProperties(Properties properties, Path target, String comment) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, comment);
            }
            PreferencesStore.moveAtomically(temporary, target);
        } catch (IOException ignored) {
            deleteQuietly(temporary);
        }
    }

    private static int parseInt(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(Properties properties, String key, double fallback) {
        try {
            return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String encode(String value) {
        String safe = value == null ? "" : value;
        return Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isBlank()) return "";
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void cancelPending() {
        if (pendingWorkspace != null) pendingWorkspace.cancel(false);
        if (pendingActivity != null) pendingActivity.cancel(false);
        pendingWorkspace = null;
        pendingActivity = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        saveNow();
        writer.shutdown();
        try {
            writer.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record WorkspaceSnapshot(
            ObfuscationConfig configuration,
            String profile,
            String goal,
            double outputSizeLimitMb,
            double runtimeOverheadPreference,
            boolean dirty
    ) {}

    private record ActivitySnapshot(List<BuildRecord> history, List<LogEntry> logs) {}
}
