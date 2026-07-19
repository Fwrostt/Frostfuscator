package dev.frost.obfuscator.jni.patcher;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resources and metadata produced by a jar patching pass.
 */
public record PatchResult(
        Map<String, byte[]> injectedResources,
        Map<String, byte[]> patchedClasses,
        List<NativeMethodPlan> nativeMethods
) {
    public PatchResult {
        injectedResources = Map.copyOf(Objects.requireNonNull(injectedResources, "injectedResources"));
        patchedClasses = Map.copyOf(Objects.requireNonNull(patchedClasses, "patchedClasses"));
        nativeMethods = List.copyOf(Objects.requireNonNull(nativeMethods, "nativeMethods"));
    }
}


