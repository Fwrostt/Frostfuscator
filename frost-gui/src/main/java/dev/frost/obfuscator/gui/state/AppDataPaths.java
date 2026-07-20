package dev.frost.obfuscator.gui.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns every durable Frostfuscator desktop path. Keeping this in one place
 * prevents individual features from leaking state into the registry, home
 * directory, or the working project.
 */
public record AppDataPaths(Path root) {
    public static final String DIRECTORY_NAME = ".frostfuscator";

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

    public Path preferences() { return root.resolve("preferences.properties"); }
    public Path workspaceDirectory() { return root.resolve("workspace"); }
    public Path sessionConfig() { return workspaceDirectory().resolve("session.yml"); }
    public Path sessionMetadata() { return workspaceDirectory().resolve("session.properties"); }
    public Path historyDirectory() { return root.resolve("history"); }
    public Path buildHistory() { return historyDirectory().resolve("build-history.properties"); }
    public Path logsDirectory() { return root.resolve("logs"); }
    public Path latestLog() { return logsDirectory().resolve("latest-session.properties"); }
    public Path themesDirectory() { return root.resolve("themes"); }
    public Path cacheDirectory() { return root.resolve("cache"); }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(workspaceDirectory());
        Files.createDirectories(historyDirectory());
        Files.createDirectories(logsDirectory());
        Files.createDirectories(themesDirectory());
        Files.createDirectories(cacheDirectory());
    }
}
