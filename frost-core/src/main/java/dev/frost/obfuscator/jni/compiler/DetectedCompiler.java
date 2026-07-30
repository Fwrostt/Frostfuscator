package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Compiler discovered on the host.
 */
public record DetectedCompiler(CompilerKind kind, Path executable, String displayName, String version) {
    public DetectedCompiler(CompilerKind kind, Path executable, String displayName) {
        this(kind, executable, displayName, "");
    }

    public DetectedCompiler {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(displayName, "displayName");
        version = version == null ? "" : version;
    }
}


