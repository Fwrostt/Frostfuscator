package dev.frost.obfuscator.jni.loader;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-side model of a native method cache. The C++ runtime header contains the
 * JNI implementation used by generated sources.
 */
public final class MethodCache {
    private final Map<NativeDescriptorLookup, String> entries = new ConcurrentHashMap<>();

    public void put(NativeDescriptorLookup lookup, String symbolName) {
        entries.put(lookup, symbolName);
    }

    public Optional<String> get(NativeDescriptorLookup lookup) {
        return Optional.ofNullable(entries.get(lookup));
    }
}


