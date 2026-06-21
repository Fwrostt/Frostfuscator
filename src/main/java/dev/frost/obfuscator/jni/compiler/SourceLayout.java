package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;

/**
 * Staged native source layout consumed by compiler backends.
 */
public record SourceLayout(Path root, Path classesDirectory, Path runtimeDirectory, Path includeDirectory, Path headersDirectory) {
}


