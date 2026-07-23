package dev.frost.runtime;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class SelfChecksumRuntime {
    private static final String INDEX = "META-INF/frostfuscator/runtime-checksums.tsv";
    private static volatile Map<String, String> checksums;

    private SelfChecksumRuntime() {
    }

    public static void verify(Class<?> anchor, String failureAction) {
        if (anchor == null) {
            fail("Missing checksum anchor", failureAction);
            return;
        }
        String internalName = anchor.getName().replace('.', '/');
        String expected = loadIndex(anchor.getClassLoader()).get(internalName);
        if (expected == null) {
            fail("Missing runtime checksum for " + internalName, failureAction);
            return;
        }
        String classFileName = internalName.substring(internalName.lastIndexOf('/') + 1) + ".class";
        try (InputStream input = anchor.getResourceAsStream(classFileName)) {
            if (input == null) {
                fail("Missing runtime class bytes for " + internalName, failureAction);
                return;
            }
            String actual = hex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            if (!constantTimeEquals(expected, actual)) {
                fail("Runtime checksum mismatch for " + internalName, failureAction);
            }
        } catch (Throwable throwable) {
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            fail("Runtime checksum failed for " + internalName, failureAction);
        }
    }

    private static Map<String, String> loadIndex(ClassLoader loader) {
        Map<String, String> snapshot = checksums;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (SelfChecksumRuntime.class) {
            snapshot = checksums;
            if (snapshot != null) {
                return snapshot;
            }
            Map<String, String> loaded = new LinkedHashMap<>();
            ClassLoader effectiveLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
            try (InputStream input = effectiveLoader.getResourceAsStream(INDEX)) {
                if (input != null) {
                    String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : text.split("\\R")) {
                        if (line.isBlank()) {
                            continue;
                        }
                        String[] parts = line.split("\\t", 2);
                        if (parts.length == 2) {
                            loaded.put(parts[0], parts[1]);
                        }
                    }
                }
            } catch (Throwable ignored) {
                loaded.clear();
            }
            checksums = loaded;
            return loaded;
        }
    }

    private static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xff;
            out[i * 2] = alphabet[value >>> 4];
            out[i * 2 + 1] = alphabet[value & 0xf];
        }
        return new String(out);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        int diff = left.length() ^ right.length();
        int length = Math.min(left.length(), right.length());
        for (int i = 0; i < length; i++) {
            diff |= left.charAt(i) ^ right.charAt(i);
        }
        return diff == 0;
    }

    private static void fail(String message, String action) {
        String normalized = action == null ? "throw" : action.toLowerCase(Locale.ROOT);
        if ("warn".equals(normalized)) {
            System.err.println(message);
            return;
        }
        if ("exit".equals(normalized)) {
            System.exit(1);
        }
        if ("halt".equals(normalized)) {
            Runtime.getRuntime().halt(1);
        }
        throw new IllegalStateException(message);
    }
}
