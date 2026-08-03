package dev.frost.ir.core;

import java.util.Objects;

/** A typed, namespaced metadata key. */
public record MetadataKey<T>(String namespace, String name, Class<T> valueType, Persistence persistence) {
    public MetadataKey {
        namespace = requirePart(namespace, "namespace");
        name = requirePart(name, "name");
        valueType = Objects.requireNonNull(valueType, "valueType");
        persistence = Objects.requireNonNull(persistence, "persistence");
    }

    public static <T> MetadataKey<T> transientKey(String namespace, String name, Class<T> type) {
        return new MetadataKey<>(namespace, name, type, Persistence.TRANSIENT);
    }

    public static <T> MetadataKey<T> persistentKey(String namespace, String name, Class<T> type) {
        return new MetadataKey<>(namespace, name, type, Persistence.PERSISTENT);
    }

    public String qualifiedName() {
        return namespace + "." + name;
    }

    private static String requirePart(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(label + " must be a non-blank identifier: " + value);
        }
        return value;
    }

    public enum Persistence { TRANSIENT, PERSISTENT }
}
