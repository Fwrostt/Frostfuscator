package dev.frost.obfuscator.gui.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathSanitizerTest {
    @TempDir
    Path tempDir;

    @Test
    void validatesPathWithinTargetDirectory() throws Exception {
        Path validPath = PathSanitizer.validatePathWithinTarget(tempDir, "com/acme/Test.java");
        assertTrue(validPath.startsWith(tempDir.toAbsolutePath().normalize()));
        assertEquals("Test.java", validPath.getFileName().toString());
    }

    @Test
    void preventsZipSlipPathTraversal() {
        assertThrows(SecurityException.class, () ->
                PathSanitizer.validatePathWithinTarget(tempDir, "../../etc/passwd"));
        assertThrows(SecurityException.class, () ->
                PathSanitizer.validatePathWithinTarget(tempDir, "../../../Windows/System32/cmd.exe"));
    }

    @Test
    void sanitizesWindowsReservedNamesAndIllegalCharacters() {
        assertEquals("_CON.java", PathSanitizer.sanitizeFilename("CON.java"));
        assertEquals("_PRN.class", PathSanitizer.sanitizeFilename("PRN.class"));
        assertEquals("_NUL.txt", PathSanitizer.sanitizeFilename("NUL.txt"));
        assertEquals("illegal_file_name_.java", PathSanitizer.sanitizeFilename("illegal:file*name?.java"));
    }

    @Test
    void checksDecompressionRatioSafety() {
        assertThrows(IOException.class, () ->
                PathSanitizer.checkDecompressionSafety(100, 3L * 1024 * 1024 * 1024)); // > 2 GB limit
        assertThrows(SecurityException.class, () ->
                PathSanitizer.checkDecompressionSafety(100, 200 * 1000 * 1000)); // > 100x compression ratio limit
    }
}
