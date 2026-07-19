package dev.frost.obfuscator.jni.core.descriptor;

import java.util.List;
import java.util.Objects;

/**
 * Parsed JVM method descriptor.
 */
public record MethodDescriptor(List<TypeDescriptor> parameters, TypeDescriptor returnType) {
    public MethodDescriptor {
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        Objects.requireNonNull(returnType, "returnType");
    }
}


