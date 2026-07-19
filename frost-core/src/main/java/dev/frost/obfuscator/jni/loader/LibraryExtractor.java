package dev.frost.obfuscator.jni.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts embedded native libraries into a temporary directory.
 */
public final class LibraryExtractor {
    public Path extract(String resourcePath, String libraryName) throws IOException {
        try (InputStream inputStream = LibraryExtractor.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing native library resource: " + resourcePath);
            }
            Path directory = Files.createTempDirectory("frostjni-natives");
            directory.toFile().deleteOnExit();
            Path library = directory.resolve(libraryName);
            Files.copy(inputStream, library, StandardCopyOption.REPLACE_EXISTING);
            library.toFile().deleteOnExit();
            return library;
        }
    }
}


