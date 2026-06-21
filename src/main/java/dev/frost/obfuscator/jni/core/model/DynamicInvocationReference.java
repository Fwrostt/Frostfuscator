package dev.frost.obfuscator.jni.core.model;

import java.util.List;
import java.util.Objects;

/**
 * JVM invokedynamic reference captured for future lowering.
 */
public record DynamicInvocationReference(
        String name,
        String descriptor,
        String bootstrapOwner,
        String bootstrapName,
        String bootstrapDescriptor,
        List<Object> bootstrapArguments
) {
    public DynamicInvocationReference {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(bootstrapOwner, "bootstrapOwner");
        Objects.requireNonNull(bootstrapName, "bootstrapName");
        Objects.requireNonNull(bootstrapDescriptor, "bootstrapDescriptor");
        bootstrapArguments = List.copyOf(Objects.requireNonNull(bootstrapArguments, "bootstrapArguments"));
    }

    public DynamicInvocationReference(String name, String descriptor) {
        this(name, descriptor, "", "", "", List.of());
    }

    public boolean isStringConcatFactory() {
        return "java/lang/invoke/StringConcatFactory".equals(bootstrapOwner)
                && ("makeConcat".equals(bootstrapName) || "makeConcatWithConstants".equals(bootstrapName));
    }
}


