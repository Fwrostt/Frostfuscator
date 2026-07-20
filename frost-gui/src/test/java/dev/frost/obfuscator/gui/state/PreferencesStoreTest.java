package dev.frost.obfuscator.gui.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreferencesStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsPreferencesAndRecentProjectsInsideTheSuppliedAppDataRoot() {
        try (PreferencesStore store = new PreferencesStore(temporaryDirectory)) {
            store.put("theme.id", "graphite");
            store.putBoolean("sidebar.collapsed", true);
            store.putDouble("ui.fontScale", 1.15);
            store.rememberProject("D:\\projects\\first.jar");
            store.rememberProject("D:\\projects\\second.jar");
            store.flush();
        }

        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("preferences.properties")));
        try (PreferencesStore restored = new PreferencesStore(temporaryDirectory)) {
            assertEquals("graphite", restored.get("theme.id", ""));
            assertTrue(restored.getBoolean("sidebar.collapsed", false));
            assertEquals(1.15, restored.getDouble("ui.fontScale", 1), 0.001);
            assertEquals(List.of("D:\\projects\\second.jar", "D:\\projects\\first.jar"),
                    restored.recentProjects());
        }
    }

    @Test
    void enumeratesCustomThemeKeysWithoutUsingTheRegistry() {
        try (PreferencesStore store = new PreferencesStore(temporaryDirectory)) {
            store.put("customTheme.custom-blue", "blue");
            store.put("customTheme.custom-gray", "gray");
            store.put("theme.id", "custom-blue");
            assertEquals(2, store.keysWithPrefix("customTheme.").size());
        }
    }
}
