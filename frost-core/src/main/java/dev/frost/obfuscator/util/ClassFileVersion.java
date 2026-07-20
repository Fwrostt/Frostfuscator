package dev.frost.obfuscator.util;

import org.objectweb.asm.Opcodes;

import java.io.IOException;

public final class ClassFileVersion {
    public static final int MIN_SUPPORTED_MAJOR = 45;
    public static final int MAX_SUPPORTED_MAJOR = Opcodes.V26;

    private ClassFileVersion() {
    }

    public static int major(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 8
                || bytes[0] != (byte) 0xCA || bytes[1] != (byte) 0xFE
                || bytes[2] != (byte) 0xBA || bytes[3] != (byte) 0xBE) {
            throw new IOException("Invalid JVM class file header");
        }
        return ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
    }

    public static void requireSupported(byte[] bytes, String source) throws IOException {
        int major = major(bytes);
        if (major < MIN_SUPPORTED_MAJOR || major > MAX_SUPPORTED_MAJOR) {
            throw new IOException(message(source, major));
        }
    }

    public static String message(String source, int major) {
        String location = source == null || source.isBlank() ? "Class file" : source;
        return location + " uses class file major version " + major + " (Java "
                + javaVersion(major) + "), but this Frostfuscator build supports Java "
                + javaVersion(MIN_SUPPORTED_MAJOR) + " through Java "
                + javaVersion(MAX_SUPPORTED_MAJOR) + " bytecode (major "
                + MIN_SUPPORTED_MAJOR + "–" + MAX_SUPPORTED_MAJOR + ").";
    }

    public static int javaVersion(int major) {
        return Math.max(1, major - 44);
    }
}
