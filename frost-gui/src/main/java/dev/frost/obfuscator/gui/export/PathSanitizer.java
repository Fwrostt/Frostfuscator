package dev.frost.obfuscator.gui.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hardened path sanitization and archive security utility inspired by JADX.
 * Protects against Zip-Slip vulnerabilities, OS-illegal filenames, Windows reserved names,
 * and Zip-bomb decompression attacks.
 */
public final class PathSanitizer {
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private static final Pattern ILLEGAL_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F]");

    private static final long MAX_UNCOMPRESSED_SIZE_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB limit per entry
    private static final double MAX_COMPRESSION_RATIO = 100.0; // 100x threshold for Zip-bomb check

    private PathSanitizer() {}

    /**
     * Ensures target output path is strictly contained within the base export directory.
     * Prevents Zip-Slip path traversal attacks.
     */
    public static Path validatePathWithinTarget(Path baseDir, String relativePath) throws IOException {
        if (relativePath != null && (relativePath.contains("..") || relativePath.startsWith("/") || relativePath.startsWith("\\"))) {
            throw new SecurityException("Zip-Slip vulnerability detected: Path traversal outside base export directory: " + relativePath);
        }
        Path resolved = baseDir.resolve(sanitizeRelativePath(relativePath)).normalize();
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        if (!resolved.toAbsolutePath().normalize().startsWith(normalizedBase)) {
            throw new SecurityException("Zip-Slip vulnerability detected: Path traversal outside base export directory: " + relativePath);
        }
        return resolved;
    }

    /**
     * Sanitizes a zip entry relative path by sanitizing each directory/filename component.
     */
    public static String sanitizeRelativePath(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return "unnamed_entry";
        }
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("/");
        StringBuilder safePath = new StringBuilder();
        for (String part : parts) {
            if (part.equals("..") || part.equals(".")) {
                continue;
            }
            String safePart = sanitizeFilename(part);
            if (!safePart.isEmpty()) {
                if (!safePath.isEmpty()) {
                    safePath.append('/');
                }
                safePath.append(safePart);
            }
        }
        return safePath.isEmpty() ? "unnamed_file" : safePath.toString();
    }

    /**
     * Sanitizes individual filename components to be valid across Windows, Linux, and macOS.
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        String sanitized = ILLEGAL_FILENAME_CHARS.matcher(filename).replaceAll("_");
        sanitized = sanitized.trim();
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.isEmpty()) {
            return "unnamed";
        }
        String baseName = sanitized;
        int dotIndex = sanitized.indexOf('.');
        if (dotIndex != -1) {
            baseName = sanitized.substring(0, dotIndex);
        }
        if (WINDOWS_RESERVED_NAMES.contains(baseName.toUpperCase())) {
            sanitized = "_" + sanitized;
        }
        return sanitized;
    }

    /**
     * Mitigates Zip-Bomb decompression attacks by checking entry size and expansion ratio.
     */
    public static void checkDecompressionSafety(long compressedSize, long uncompressedSize) throws IOException {
        if (uncompressedSize > MAX_UNCOMPRESSED_SIZE_BYTES) {
            throw new IOException("Oversized decompression entry detected (" + uncompressedSize + " bytes > limit " + MAX_UNCOMPRESSED_SIZE_BYTES + " bytes)");
        }
        if (compressedSize > 0 && uncompressedSize > 1024 * 1024) {
            double ratio = (double) uncompressedSize / compressedSize;
            if (ratio > MAX_COMPRESSION_RATIO) {
                throw new SecurityException("Potential Zip-bomb detected (compression ratio " + String.format("%.1f", ratio) + "x exceeds safety limit " + MAX_COMPRESSION_RATIO + "x)");
            }
        }
    }
}
