package dev.frost.obfuscator.jni.patcher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic mapping from JVM methods to generated JNI symbols.
 */
public final class MethodMappingRegistry {
    private final Map<MethodKey, NativeMethodPlan> mappings = new LinkedHashMap<>();

    public static MethodMappingRegistry fromPlans(List<NativeMethodPlan> methods) {
        MethodMappingRegistry registry = new MethodMappingRegistry();
        for (NativeMethodPlan method : methods) {
            registry.register(method);
        }
        return registry;
    }

    public void register(NativeMethodPlan method) {
        mappings.put(new MethodKey(method.ownerInternalName(), method.name(), method.descriptor()), method);
    }

    public Optional<String> find(String ownerInternalName, String name, String descriptor) {
        return Optional.ofNullable(mappings.get(new MethodKey(ownerInternalName, name, descriptor)))
                .map(NativeMethodPlan::nativeSymbol);
    }

    public boolean contains(String ownerInternalName, String name, String descriptor) {
        return mappings.containsKey(new MethodKey(ownerInternalName, name, descriptor));
    }

    public List<NativeMethodPlan> asPlans() {
        return List.copyOf(mappings.values());
    }

    private record MethodKey(String ownerInternalName, String name, String descriptor) {
    }
}


