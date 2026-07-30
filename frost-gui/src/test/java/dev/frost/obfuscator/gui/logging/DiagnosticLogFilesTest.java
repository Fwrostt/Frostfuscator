package dev.frost.obfuscator.gui.logging;

import dev.frost.obfuscator.gui.console.LogEntry;
import dev.frost.obfuscator.gui.state.AppDataPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticLogFilesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void everyCrashGetsANewTimestampedFile() throws Exception {
        AppDataPaths paths = new AppDataPaths(temporaryDirectory);

        Path first = DiagnosticLogFiles.writeCrash(paths, new IllegalStateException("first"), Thread.currentThread());
        Path second = DiagnosticLogFiles.writeCrash(paths, new IllegalStateException("second"), Thread.currentThread());

        assertNotEquals(first, second);
        assertTrue(Files.readString(first).contains("IllegalStateException: first"));
        assertTrue(Files.readString(second).contains("IllegalStateException: second"));
    }

    @Test
    void buildSessionWritesInsideTheConfiguredAppDataRoot() throws Exception {
        AppDataPaths paths = new AppDataPaths(temporaryDirectory);
        Path log;
        try (DiagnosticLogFiles.Session session = DiagnosticLogFiles.startBuild(paths)) {
            log = session.path();
            session.append(new LogEntry(LocalDateTime.now(), LogEntry.Level.INFO,
                    "method-renamer", "Renamed 12 methods", ""));
        }

        assertTrue(log.startsWith(paths.buildLogsDirectory()));
        assertTrue(Files.readString(log).contains("Renamed 12 methods"));
    }
}
