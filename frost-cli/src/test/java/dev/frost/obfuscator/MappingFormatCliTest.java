package dev.frost.obfuscator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MappingFormatCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsStandardMappingFormatsDuringDryRun() throws Exception {
        Path input = temporaryDirectory.resolve("input.jar");
        Files.write(input, new byte[0]);
        Path output = temporaryDirectory.resolve("output.jar");

        for (String format : new String[]{"yaml", "proguard", "tiny"}) {
            int exit = new CommandLine(new Main()).execute(
                    "--input", input.toString(),
                    "--output", output.toString(),
                    "--dry-run",
                    "--export-mapping-format", format);
            assertEquals(0, exit, format);
        }
    }

    @Test
    void rejectsUnknownMappingFormat() throws Exception {
        Path input = temporaryDirectory.resolve("input.jar");
        Files.write(input, new byte[0]);
        int exit = new CommandLine(new Main()).execute(
                "--input", input.toString(),
                "--output", temporaryDirectory.resolve("output.jar").toString(),
                "--dry-run",
                "--export-mapping-format", "unknown");
        assertEquals(1, exit);
    }
}
