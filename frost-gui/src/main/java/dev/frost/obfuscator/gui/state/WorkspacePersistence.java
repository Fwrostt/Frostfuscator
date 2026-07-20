package dev.frost.obfuscator.gui.state;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ConfigWriter;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.config.ConfigurationBinder;
import dev.frost.obfuscator.gui.console.ConsoleModel;
import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Autosaves the working configuration and useful session activity without
 * blocking the JavaFX application thread on disk access.
 */
public final class WorkspacePersistence implements AutoCloseable {
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
    private volatile WorkspaceSnapshot latestWorkspace;
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
        applyRestore(loadRestore());
    }

    /**
     * Reads session files away from the JavaFX application thread, then applies
     * the parsed snapshot in one short FX-thread transaction.
     */
    public CompletableFuture<Void> restoreAsync() {
        return CompletableFuture.supplyAsync(this::loadRestore, writer)
                .thenCompose(snapshot -> {
                    CompletableFuture<Void> applied = new CompletableFuture<>();
                    Platform.runLater(() -> {
                        try {
                            if (!closed) {
                                applyRestore(snapshot);
                                start();
                            }
                            applied.complete(null);
                        } catch (RuntimeException exception) {
                            applied.completeExceptionally(exception);
                        }
                    });
                    return applied;
                });
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
        captureWorkspace();
    }

    public void saveNow() {
        captureWorkspace();
        cancelPending();
        WorkspaceSnapshot workspace = latestWorkspace;
        if (workspace != null) writeWorkspace(workspace);
    }

    private RestoreSnapshot loadRestore() {
        Properties metadata = loadProperties(paths.sessionMetadata());
        ObfuscationConfig configuration = null;
        if (Files.isRegularFile(paths.sessionConfig())) {
            try {
                configuration = ConfigLoader.load(paths.sessionConfig());
                ConfigurationBinder.ensureAllTransformers(configuration);
            } catch (RuntimeException ignored) {
                // Keep the known-good default configuration when an autosave is damaged.
            }
        }
        if (configuration != null) clearTransientProjectFields(configuration);
        deleteQuietly(paths.buildHistory());
        deleteQuietly(paths.latestLog());
        return new RestoreSnapshot(configuration, metadata);
    }

    private void applyRestore(RestoreSnapshot snapshot) {
        if (snapshot.configuration() != null) state.replaceConfiguration(snapshot.configuration());
        Properties metadata = snapshot.metadata();
        state.profileProperty().set(metadata.getProperty("profile", state.profileProperty().get()));
        state.goalProperty().set(metadata.getProperty("goal", state.goalProperty().get()));
        state.outputSizeLimitMbProperty().set(parseDouble(metadata, "outputSizeLimitMb",
                state.outputSizeLimitMbProperty().get()));
        state.runtimeOverheadPreferenceProperty().set(parseDouble(metadata, "runtimeOverheadPreference",
                state.runtimeOverheadPreferenceProperty().get()));
        state.dirtyProperty().set(false);
        state.buildHistory().clear();
        console.clear();
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

    private void captureWorkspace() {
        ObfuscationConfig configuration = binder.snapshot();
        clearTransientProjectFields(configuration);
        latestWorkspace = new WorkspaceSnapshot(
                configuration,
                state.profileProperty().get(),
                state.goalProperty().get(),
                state.outputSizeLimitMbProperty().get(),
                state.runtimeOverheadPreferenceProperty().get());
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
        storeProperties(metadata, paths.sessionMetadata(), "Frostfuscator workspace session");
    }

    private static void clearTransientProjectFields(ObfuscationConfig configuration) {
        configuration.setInput("");
        configuration.setOutput("");
        configuration.setLibs("");
        configuration.getLibraries().setPaths(new ArrayList<>());
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

    private static double parseDouble(Properties properties, String key, double fallback) {
        try {
            return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void cancelPending() {
        if (pendingWorkspace != null) pendingWorkspace.cancel(false);
        pendingWorkspace = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (started) saveNow();
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
            double runtimeOverheadPreference
    ) {}

    private record RestoreSnapshot(
            ObfuscationConfig configuration,
            Properties metadata
    ) {}
}
