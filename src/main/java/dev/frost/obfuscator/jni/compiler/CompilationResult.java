package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Result of a native compilation attempt.
 */
public record CompilationResult(
        List<NativeLibrary> libraries,
        Path buildDirectory,
        Path cmakeListsPath,
        int exitCode,
        String stdout,
        String stderr
) {
    public CompilationResult {
        libraries = List.copyOf(Objects.requireNonNull(libraries, "libraries"));
        Objects.requireNonNull(buildDirectory, "buildDirectory");
        Objects.requireNonNull(cmakeListsPath, "cmakeListsPath");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    public boolean success() {
        return exitCode == 0;
    }
}


