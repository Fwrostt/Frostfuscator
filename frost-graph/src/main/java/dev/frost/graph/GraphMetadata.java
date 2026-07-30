package dev.frost.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable string-keyed metadata attached to graphs, nodes, and edges. */
public record GraphMetadata(Map<String, Object> values) {
    public static final GraphMetadata EMPTY = new GraphMetadata(Map.of());

    public GraphMetadata {
        values = values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Object get(String key) {
        return values.get(key);
    }

    public String string(String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value.toString();
    }

    public boolean bool(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean booleanValue ? booleanValue
                : value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Builder put(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) values.put(key, value);
            return this;
        }

        public Builder putAll(Map<String, ?> source) {
            if (source != null) source.forEach(this::put);
            return this;
        }

        public GraphMetadata build() {
            return values.isEmpty() ? EMPTY : new GraphMetadata(values);
        }
    }
}
