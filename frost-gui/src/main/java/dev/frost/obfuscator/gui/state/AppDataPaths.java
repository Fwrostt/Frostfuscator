package dev.frost.obfuscator.gui.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Owns every durable Frostfuscator desktop path. Keeping this in one place
 * prevents individual features from leaking state into the registry, home
 * directory, or the working project.
 */
public record AppDataPaths(Path root) {
    public static final String DIRECTORY_NAME = ".frostfuscator";
    private static final String LOCATION_FILE = "storage-location.txt";

    public AppDataPaths {
        root = root.toAbsolutePath().normalize();
    }

    public static AppDataPaths systemDefault() {
        String appData = System.getenv("APPDATA");
        Path base;
        if (appData != null && !appData.isBlank()) {
            base = Path.of(appData);
        } else if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            base = Path.of(System.getProperty("user.home"), "AppData", "Roaming");
        } else {
            base = Path.of(System.getProperty("user.home"));
        }
        return new AppDataPaths(base.resolve(DIRECTORY_NAME));
    }

    /** Returns the user-selected data root, falling back safely to the system default. */
    public static AppDataPaths configured() {
        return configured(systemDefault());
    }

    static AppDataPaths configured(AppDataPaths fallback) {
        Path locationFile = fallback.root().resolve(LOCATION_FILE);
        if (Files.isRegularFile(locationFile)) {
            try {
                String selected = Files.readString(locationFile, StandardCharsets.UTF_8).trim();
                if (!selected.isBlank()) {
                    Path selectedPath = Path.of(selected);
                    if (!selectedPath.isAbsolute()) throw new IOException("Storage location must be absolute");
                    AppDataPaths candidate = new AppDataPaths(selectedPath);
                    candidate.ensureDirectories();
                    return candidate;
                }
            } catch (Exception ignored) {
                // A removed drive or damaged redirect must not prevent startup.
            }
        }
        try {
            fallback.ensureDirectories();
        } catch (IOException ignored) {
            // Callers still get the conventional location for useful diagnostics.
        }
        return fallback;
    }

    public Path preferences() { return root.resolve("preferences.properties"); }
    public Path workspaceDirectory() { return root.resolve("workspace"); }
    public Path sessionConfig() { return workspaceDirectory().resolve("session.yml"); }
    public Path sessionMetadata() { return workspaceDirectory().resolve("session.properties"); }
    public Path historyDirectory() { return root.resolve("history"); }
    public Path buildHistory() { return historyDirectory().resolve("build-history.properties"); }
    public Path logsDirectory() { return root.resolve("logs"); }
    public Path crashLogsDirectory() { return logsDirectory().resolve("crashes"); }
    public Path buildLogsDirectory() { return logsDirectory().resolve("builds"); }
    public Path latestLog() { return logsDirectory().resolve("latest-session.properties"); }
    public Path themesDirectory() { return root.resolve("themes"); }
    public Path cacheDirectory() { return root.resolve("cache"); }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(workspaceDirectory());
        Files.createDirectories(historyDirectory());
        Files.createDirectories(logsDirectory());
        Files.createDirectories(crashLogsDirectory());
        Files.createDirectories(buildLogsDirectory());
        Files.createDirectories(themesDirectory());
        Files.createDirectories(cacheDirectory());
    }

    /**
     * Copies durable data to a new root and records it for the next launch.
     * The old root is deliberately retained as a recoverable backup.
     */
    public AppDataPaths relocateTo(Path selectedRoot) throws IOException {
        return relocateTo(selectedRoot, systemDefault());
    }

    AppDataPaths relocateTo(Path selectedRoot, AppDataPaths fallback) throws IOException {
        AppDataPaths destination = new AppDataPaths(selectedRoot);
        if (destination.root().equals(root)) return this;
        if (destination.root().startsWith(root)) {
            throw new IOException("Choose a folder outside the current Frostfuscator data folder.");
        }
        copyTo(destination);
        rememberRoot(destination.root(), fallback);
        return destination;
    }

    public AppDataPaths relocateToDefault() throws IOException {
        AppDataPaths destination = systemDefault();
        if (!destination.root().equals(root)) {
            copyTo(destination);
        }
        Files.deleteIfExists(destination.root().resolve(LOCATION_FILE));
        return destination;
    }

    private void copyTo(AppDataPaths destination) throws IOException {
        ensureDirectories();
        destination.ensureDirectories();
        try (Stream<Path> sourcePaths = Files.walk(root)) {
            for (Path source : sourcePaths.toList()) {
                Path relative = root.relativize(source);
                if (relative.toString().equals(LOCATION_FILE) || Files.isSymbolicLink(source)) continue;
                Path target = destination.root().resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    static void rememberRoot(Path selectedRoot, AppDataPaths fallback) throws IOException {
        fallback.ensureDirectories();
        Path target = fallback.root().resolve(LOCATION_FILE);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, selectedRoot.toAbsolutePath().normalize() + System.lineSeparator(),
                StandardCharsets.UTF_8);
        PreferencesStore.moveAtomically(temporary, target);
    }
}
