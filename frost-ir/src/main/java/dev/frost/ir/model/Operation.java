package dev.frost.ir.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable operation instance. Operands and results belong to the containing instruction. */
public record Operation(OperationCode code, Map<String, IrAttribute> attributes) {
    public Operation {
        code = Objects.requireNonNull(code, "code");
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        if (attributes.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("attribute names must be non-blank");
        }
    }

    public Operation(OperationCode code) { this(code, Map.of()); }

    public Operation withAttribute(String name, IrAttribute value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Map<String, IrAttribute> updated = new LinkedHashMap<>(attributes);
        updated.put(name, value);
        return new Operation(code, updated);
    }
}
