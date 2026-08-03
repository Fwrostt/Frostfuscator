package dev.frost.ir.model;

import dev.frost.ir.type.MethodType;
import java.util.List;
import java.util.Objects;

/** Class-file method identity and semantic signature. Internal JVM names are used throughout. */
public record MethodSignature(String owner, String name, MethodType type, int access,
                              String genericSignature, List<String> declaredExceptions) {
    public MethodSignature {
        owner = requireInternalName(owner, "owner");
        name = Objects.requireNonNull(name, "name");
        type = Objects.requireNonNull(type, "type");
        declaredExceptions = declaredExceptions == null ? List.of() : List.copyOf(declaredExceptions);
        if (name.isBlank()) throw new IllegalArgumentException("method name must not be blank");
        declaredExceptions.forEach(value -> requireInternalName(value, "declared exception"));
    }

    public String qualifiedName() {
        return owner + "." + name + type.displayName();
    }

    private static String requireInternalName(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.indexOf('.') >= 0 || value.startsWith("[")) {
            throw new IllegalArgumentException("Invalid " + label + " internal name: " + value);
        }
        return value;
    }
}
