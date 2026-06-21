package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Result of a compiler backend invocation.
 */
public record CompilerResult(List<NativeLibrary> libraries, Path manifestPath) {
    public CompilerResult {
        libraries = List.copyOf(Objects.requireNonNull(libraries, "libraries"));
        Objects.requireNonNull(manifestPath, "manifestPath");
    }
}


