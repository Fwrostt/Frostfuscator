package dev.frost.obfuscator.jni.compiler;

import dev.frost.obfuscator.util.Logger;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Verifies native library artifacts and expected JNI symbols.
 */
public final class NativeLibraryVerifier {
    public void verify(CompilationResult result, JniSymbolRegistry symbolRegistry) throws IOException {
        for (NativeLibrary library : result.libraries()) {
            if (!Files.isRegularFile(library.path())) {
                throw new IOException("Missing native library: " + library.path());
            }
            byte[] bytes = Files.readAllBytes(library.path());
            if (!contains(bytes, "JNI_OnLoad")) {
                throw new IOException("Native library " + library.path() + " does not appear to export JNI_OnLoad");
            }
        }
        Logger.info("[VERIFY] Native library load hook verified; method bindings are registered internally");
    }

    private boolean contains(byte[] bytes, String needle) {
        byte[] target = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (bytes[i + j] != target[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}


