package dev.frost.obfuscator.jni.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads FrostJNI native runtime resources bundled with the Java artifact.
 */
public final class RuntimeResource {
    public String loadHeader() {
        return loadText("/native/frostjni_runtime.hpp");
    }

    private String loadText(String resourceName) {
        try (InputStream inputStream = RuntimeResource.class.getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing runtime resource: " + resourceName);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read runtime resource: " + resourceName, exception);
        }
    }
}


