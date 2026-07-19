package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input directory and target matrix for native compilation.
 */
public record CompilerInput(
        Path sourceDirectory,
        Path outputDirectory,
        List<TargetPlatform> targets,
        String libraryBaseName,
        boolean unityBuild,
        String optimizationLevel,
        boolean stripSymbols
) {
    public CompilerInput(Path sourceDirectory, Path outputDirectory, List<TargetPlatform> targets, String libraryBaseName) {
        this(sourceDirectory, outputDirectory, targets, libraryBaseName, true, "O0", false);
    }

    public CompilerInput {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        Objects.requireNonNull(libraryBaseName, "libraryBaseName");
        optimizationLevel = optimizationLevel == null || optimizationLevel.isBlank() ? "O0" : optimizationLevel;
    }
}


