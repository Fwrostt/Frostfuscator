package dev.frost.obfuscator.jni.loader;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-side model of native class cache entries.
 */
public final class ClassCache {
    private final Map<String, String> entries = new ConcurrentHashMap<>();

    public void put(String ownerInternalName, String symbolName) {
        entries.put(ownerInternalName, symbolName);
    }

    public Optional<String> get(String ownerInternalName) {
        return Optional.ofNullable(entries.get(ownerInternalName));
    }
}


