package dev.frost.obfuscator.jni.core.model;

import java.util.Objects;

/**
 * JVM field reference operand.
 */
public record FieldReference(String ownerInternalName, String name, String descriptor) {
    public FieldReference {
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}


