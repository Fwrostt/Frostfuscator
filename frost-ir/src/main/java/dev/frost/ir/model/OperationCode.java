package dev.frost.ir.model;

import java.util.Objects;

/** Stable namespaced operation identity, suitable for plugins and serialized IR. */
public record OperationCode(String namespace, String name, int version) implements Comparable<OperationCode> {
    public OperationCode {
        namespace = requirePart(namespace, "namespace");
        name = requirePart(name, "name");
        if (version < 1) throw new IllegalArgumentException("operation version must be positive");
    }

    public OperationCode(String namespace, String name) {
        this(namespace, name, 1);
    }

    public String qualifiedName() {
        return namespace + "." + name;
    }

    @Override
    public int compareTo(OperationCode other) {
        int byName = qualifiedName().compareTo(other.qualifiedName());
        return byName != 0 ? byName : Integer.compare(version, other.version);
    }

    private static String requirePart(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || !value.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException(label + " is not a valid operation identifier: " + value);
        }
        return value;
    }
}
