package dev.frost.obfuscator.jni.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads native libraries embedded in protected FrostJNI jars.
 */
public final class NativeLoader {
    private static final ConcurrentMap<String, Boolean> LOADED = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Path> EXTRACTED = new ConcurrentHashMap<>();
    private static final LibraryExtractor EXTRACTOR = new LibraryExtractor();

    private NativeLoader() {
    }

    public static void load(String libraryBaseName) {
        LOADED.computeIfAbsent(libraryBaseName, NativeLoader::loadOnce);
    }

    private static boolean loadOnce(String libraryBaseName) {
        String libraryName = System.mapLibraryName(libraryBaseName);
        String resourcePath = "native/" + osName() + "/" + archName() + "/" + libraryName;
        try {
            Path extracted = EXTRACTED.computeIfAbsent(resourcePath, ignored -> extract(resourcePath, libraryName));
            System.load(extracted.toAbsolutePath().toString());
            return true;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to extract FrostJNI native library " + resourcePath, exception);
        }
    }

    private static Path extract(String resourcePath, String libraryName) {
        try {
            return EXTRACTOR.extract(resourcePath, libraryName);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String osName() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    private static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return "x86_64";
    }
}


