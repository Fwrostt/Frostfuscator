package dev.frost.ir.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministically ordered typed metadata. Mutation is owned by the containing IR entity. */
public final class MetadataMap {
    private final Map<MetadataKey<?>, Object> values = new LinkedHashMap<>();
    private final Runnable mutationCallback;

    public MetadataMap(Runnable mutationCallback) {
        this.mutationCallback = Objects.requireNonNull(mutationCallback, "mutationCallback");
    }

    public <T> Optional<T> get(MetadataKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(key.valueType().cast(value));
    }

    public <T> void put(MetadataKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (!key.valueType().isInstance(value)) {
            throw new IllegalArgumentException("Value for " + key.qualifiedName() + " must be "
                    + key.valueType().getName() + ", got " + value.getClass().getName());
        }
        Object previous = values.put(key, value);
        if (!Objects.equals(previous, value)) mutationCallback.run();
    }

    public boolean remove(MetadataKey<?> key) {
        Objects.requireNonNull(key, "key");
        if (values.remove(key) != null) {
            mutationCallback.run();
            return true;
        }
        return false;
    }

    public Map<MetadataKey<?>, Object> view() {
        return Collections.unmodifiableMap(values);
    }

    public Map<MetadataKey<?>, Object> persistentView() {
        Map<MetadataKey<?>, Object> persistent = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.persistence() == MetadataKey.Persistence.PERSISTENT) persistent.put(key, value);
        });
        return Collections.unmodifiableMap(persistent);
    }

    /** Copies persistent entries while retaining their runtime key types. */
    public void copyPersistentTo(MetadataMap target) {
        Objects.requireNonNull(target, "target");
        persistentView().forEach(target::putUnchecked);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void putUnchecked(MetadataKey key, Object value) { put(key, value); }
}
