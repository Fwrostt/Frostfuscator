package dev.frost.obfuscator.jni.core.model;

import java.util.Objects;

/**
 * Stable label identifier independent of ASM's mutable LabelNode instances.
 */
public record LabelModel(String id) {
    public LabelModel {
        Objects.requireNonNull(id, "id");
    }
}


