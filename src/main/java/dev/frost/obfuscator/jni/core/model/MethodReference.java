package dev.frost.obfuscator.jni.core.model;

import java.util.Objects;

/**
 * JVM method reference operand.
 */
public record MethodReference(String ownerInternalName, String name, String descriptor, boolean isInterface) {
    public MethodReference {
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}


