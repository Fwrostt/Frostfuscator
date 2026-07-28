package dev.frost.obfuscator.gui.state;

import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

public final class PreferencesStore implements AutoCloseable {
    public static final double DEFAULT_WINDOW_WIDTH = 1180;
    public static final double DEFAULT_WINDOW_HEIGHT = 760;
    private static final String LEGACY_NODE = "dev/frost/obfuscator/gui/v2";
    private static final String MIGRATION_MARKER = "storage.migratedFromJavaPreferences";
    private final AppDataPaths paths;
    private final Properties values = new Properties();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "frostfuscator-preferences-writer");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> pendingWrite;
    private boolean closed;

    public PreferencesStore() {
        this(AppDataPaths.systemDefault(), true);
    }

    public PreferencesStore(Path root) {
        this(new AppDataPaths(root), false);
    }

    public PreferencesStore(AppDataPaths paths, boolean migrateLegacy) {
        this.paths = paths;
        try {
            paths.ensureDirectories();
        } catch (IOException ignored) {
            // Reads can still fall back to defaults in a restricted environment.
        }
        load();
        if (migrateLegacy && !getBoolean(MIGRATION_MARKER, false)) migrateLegacyPreferences();
    }

    public AppDataPaths paths() { return paths; }

    public synchronized String get(String key, String fallback) {
        return values.getProperty(key, fallback);
    }

    public synchronized void put(String key, String value) {
        if (value == null) values.remove(key);
        else values.setProperty(key, value);
        scheduleWrite();
    }

    public boolean getBoolean(String key, boolean fallback) {
        String raw = get(key, Boolean.toString(fallback));
        return raw.equalsIgnoreCase("true") ? true : raw.equalsIgnoreCase("false") ? false : fallback;
    }

    public void putBoolean(String key, boolean value) { put(key, Boolean.toString(value)); }

    public double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(get(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public void putDouble(String key, double value) { put(key, Double.toString(value)); }

    public synchronized Set<String> keysWithPrefix(String prefix) {
        Set<String> keys = new TreeSet<>();
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith(prefix)) keys.add(key);
        }
        return Set.copyOf(keys);
    }

    public void restoreWindow(Stage stage) {
        double x = getDouble("window.x", Double.NaN);
        double y = getDouble("window.y", Double.NaN);
        Screen screen = Screen.getScreens().stream()
                .filter(candidate -> Double.isFinite(x) && Double.isFinite(y)
                        && candidate.getVisualBounds().contains(x, y))
                .findFirst().orElse(Screen.getPrimary());
        javafx.geometry.Rectangle2D visual = screen.getVisualBounds();
        double storedWidth = getDouble("window.width", DEFAULT_WINDOW_WIDTH);
        double storedHeight = getDouble("window.height", DEFAULT_WINDOW_HEIGHT);
        boolean staleFullscreenBounds = storedWidth >= visual.getWidth() * 0.94
                && storedHeight >= visual.getHeight() * 0.94;
        double requestedWidth = staleFullscreenBounds ? DEFAULT_WINDOW_WIDTH : storedWidth;
        double requestedHeight = staleFullscreenBounds ? DEFAULT_WINDOW_HEIGHT : storedHeight;
        double width = Math.max(stage.getMinWidth(), Math.min(requestedWidth, visual.getWidth() - 32));
        double height = Math.max(stage.getMinHeight(), Math.min(requestedHeight, visual.getHeight() - 32));
        stage.setWidth(width);
        stage.setHeight(height);
        if (Double.isFinite(x) && Double.isFinite(y) && visual.contains(x, y)) {
            stage.setX(Math.max(visual.getMinX(), Math.min(x, visual.getMaxX() - width)));
            stage.setY(Math.max(visual.getMinY(), Math.min(y, visual.getMaxY() - height)));
        } else {
            stage.centerOnScreen();
        }
    }

    public void saveWindow(Stage stage) {
        boolean isMax = dev.frost.obfuscator.gui.titlebar.CustomTitleBar.isCustomMaximized(stage);
        putBoolean("window.maximized", isMax);
        javafx.geometry.Rectangle2D normal = isMax
                ? dev.frost.obfuscator.gui.titlebar.CustomTitleBar.normalBounds(stage)
                : new javafx.geometry.Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        if (normal == null) return;
        putDouble("window.x", normal.getMinX());
        putDouble("window.y", normal.getMinY());
        putDouble("window.width", normal.getWidth());
        putDouble("window.height", normal.getHeight());
    }

    public List<String> recentProjects() {
        String raw = get("recent.projects", "");
        if (raw.isBlank()) return List.of();
        String delimiter = raw.contains("\n") ? "\\R" : "\\|";
        return Arrays.stream(raw.split(delimiter))
                .filter(value -> !value.isBlank()).toList();
    }

    public void rememberProject(String path) {
        List<String> recent = new ArrayList<>(recentProjects());
        recent.remove(path);
        recent.add(0, path);
        if (recent.size() > 10) recent = recent.subList(0, 10);
        put("recent.projects", String.join("\n", recent));
    }

    public void flush() {
        Properties snapshot;
        synchronized (this) {
            if (pendingWrite != null) pendingWrite.cancel(false);
            pendingWrite = null;
            snapshot = copyValues();
        }
        writeSnapshot(snapshot);
    }

    private synchronized void scheduleWrite() {
        if (closed) return;
        if (pendingWrite != null) pendingWrite.cancel(false);
        pendingWrite = writer.schedule(() -> {
            Properties snapshot;
            synchronized (PreferencesStore.this) {
                pendingWrite = null;
                snapshot = copyValues();
            }
            writeSnapshot(snapshot);
        }, 180, TimeUnit.MILLISECONDS);
    }

    private void load() {
        Path file = paths.preferences();
        if (!Files.isRegularFile(file)) return;
        try (InputStream input = Files.newInputStream(file)) {
            values.load(input);
        } catch (IOException ignored) {
            // A damaged preference file must never prevent the application from opening.
        }
    }

    private void migrateLegacyPreferences() {
        try {
            Preferences legacy = Preferences.userRoot().node(LEGACY_NODE);
            for (String key : legacy.keys()) {
                synchronized (this) {
                    values.putIfAbsent(key, legacy.get(key, ""));
                }
            }
        } catch (Exception ignored) {
            // The registry may be unavailable in restricted Windows environments.
        }
        synchronized (this) {
            values.setProperty(MIGRATION_MARKER, "true");
        }
        flush();
    }

    private synchronized Properties copyValues() {
        Properties copy = new Properties();
        copy.putAll(values);
        return copy;
    }

    private void writeSnapshot(Properties snapshot) {
        Path target = paths.preferences();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(paths.root());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                snapshot.store(output, "Frostfuscator desktop preferences");
            }
            moveAtomically(temporary, target);
        } catch (IOException ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredAgain) {
            }
        }
    }

    static void moveAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        flush();
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
