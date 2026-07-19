package dev.frost.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Runtime API injected into outputs using resource-splitting.
 */
public final class SplitResourceLoader {
    private static final String INDEX = "META-INF/frostfuscator/splits/index.tsv";

    private SplitResourceLoader() {
    }

    public static byte[] read(String resourceName) throws IOException {
        ClassLoader loader = effectiveLoader();
        try (InputStream index = loader.getResourceAsStream(INDEX)) {
            if (index == null) throw new IOException("Split resource index is missing");
            String encodedName = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(resourceName.getBytes(StandardCharsets.UTF_8));
            for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String[] columns = line.split("\\t");
                if (columns.length != 4 || !columns[0].equals(encodedName)) continue;
                int count = Integer.parseInt(columns[2]);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                for (int i = 0; i < count; i++) {
                    try (InputStream part = loader.getResourceAsStream(columns[1] + i + ".bin")) {
                        if (part == null) throw new IOException("Missing split resource part " + i);
                        part.transferTo(output);
                    }
                }
                byte[] result = output.toByteArray();
                if (!hex(MessageDigest.getInstance("SHA-256").digest(result)).equals(columns[3])) {
                    throw new IOException("Split resource checksum mismatch");
                }
                return result;
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not rebuild split resource", exception);
        }
        throw new IOException("Unknown split resource: " + resourceName);
    }

    public static InputStream open(String resourceName) throws IOException {
        return new java.io.ByteArrayInputStream(read(resourceName));
    }

    private static ClassLoader effectiveLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : SplitResourceLoader.class.getClassLoader();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
