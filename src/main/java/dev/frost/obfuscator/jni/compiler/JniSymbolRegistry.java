package dev.frost.obfuscator.jni.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic registry of expected JNI symbols.
 */
public final class JniSymbolRegistry {
    private final Map<MethodKey, String> symbols = new LinkedHashMap<>();

    public void register(String ownerInternalName, String methodName, String descriptor, String jniSymbol) {
        symbols.put(new MethodKey(ownerInternalName, methodName, descriptor), jniSymbol);
    }

    public Optional<String> find(String ownerInternalName, String methodName, String descriptor) {
        return Optional.ofNullable(symbols.get(new MethodKey(ownerInternalName, methodName, descriptor)));
    }

    public List<String> symbols() {
        return List.copyOf(symbols.values());
    }

    public boolean isEmpty() {
        return symbols.isEmpty();
    }

    private record MethodKey(String ownerInternalName, String methodName, String descriptor) {
        private MethodKey {
            Objects.requireNonNull(ownerInternalName, "ownerInternalName");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}


