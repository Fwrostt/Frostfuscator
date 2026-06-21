package dev.frost.obfuscator.jni.patcher;

import java.util.Objects;

/**
 * A method selected for native protection.
 */
public record NativeMethodPlan(String ownerInternalName, String name, String descriptor, String nativeSymbol) {
    public NativeMethodPlan {
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(nativeSymbol, "nativeSymbol");
    }
}


