package dev.frost.obfuscator.jni.patcher;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates patch metadata resources embedded in protected jars.
 */
public final class BridgeMetadataGenerator {
    public Map<String, byte[]> generate(PatchPlan plan, MethodMappingRegistry registry) {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        resources.put("native/protection.properties", protectionProperties(plan, registry));
        resources.put("native/native-methods.txt", nativeMethods(registry));
        return resources;
    }

    private byte[] protectionProperties(PatchPlan plan, MethodMappingRegistry registry) {
        String content = "libraryBaseName=" + plan.libraryBaseName() + "\n"
                + "nativeMethodCount=" + registry.asPlans().size() + "\n";
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] nativeMethods(MethodMappingRegistry registry) {
        StringBuilder builder = new StringBuilder();
        for (NativeMethodPlan method : registry.asPlans()) {
            builder.append(method.ownerInternalName())
                    .append('#')
                    .append(method.name())
                    .append(method.descriptor())
                    .append('=')
                    .append(method.nativeSymbol())
                    .append('\n');
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}


