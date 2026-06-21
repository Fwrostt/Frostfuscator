package dev.frost.obfuscator.jni.patcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Planned jar/class changes for a protected artifact.
 */
public record PatchPlan(Path inputJar, String libraryBaseName, List<NativeMethodPlan> nativeMethods) {
    public PatchPlan {
        Objects.requireNonNull(inputJar, "inputJar");
        Objects.requireNonNull(libraryBaseName, "libraryBaseName");
        nativeMethods = List.copyOf(Objects.requireNonNull(nativeMethods, "nativeMethods"));
    }
}


