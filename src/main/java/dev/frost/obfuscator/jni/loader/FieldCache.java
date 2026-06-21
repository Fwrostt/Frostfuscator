package dev.frost.obfuscator.jni.loader;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-side model of a native field cache.
 */
public final class FieldCache {
    private final Map<NativeDescriptorLookup, String> entries = new ConcurrentHashMap<>();

    public void put(NativeDescriptorLookup lookup, String symbolName) {
        entries.put(lookup, symbolName);
    }

    public Optional<String> get(NativeDescriptorLookup lookup) {
        return Optional.ofNullable(entries.get(lookup));
    }
}


