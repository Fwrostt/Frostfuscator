package dev.frost.obfuscator.gui.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDataPathsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rememberedRootIsUsedAndExistingDataIsCopiedWithoutRemovingTheOriginal() throws Exception {
        AppDataPaths fallback = new AppDataPaths(temporaryDirectory.resolve("default"));
        AppDataPaths original = new AppDataPaths(temporaryDirectory.resolve("current"));
        original.ensureDirectories();
        Files.writeString(original.preferences(), "theme.id=graphite\n");

        Path selected = temporaryDirectory.resolve("selected");
        AppDataPaths moved = original.relocateTo(selected, fallback);

        assertEquals(selected.toAbsolutePath().normalize(), moved.root());
        assertTrue(Files.isRegularFile(original.preferences()), "the old copy remains recoverable");
        assertEquals("theme.id=graphite\n", Files.readString(moved.preferences()));
        assertEquals(moved, AppDataPaths.configured(fallback));
    }

    @Test
    void damagedLocationPointerFallsBackToTheDefaultRoot() throws Exception {
        AppDataPaths fallback = new AppDataPaths(temporaryDirectory.resolve("default"));
        fallback.ensureDirectories();
        Files.writeString(fallback.root().resolve("storage-location.txt"), "\0invalid");

        assertEquals(fallback, AppDataPaths.configured(fallback));
    }
}
