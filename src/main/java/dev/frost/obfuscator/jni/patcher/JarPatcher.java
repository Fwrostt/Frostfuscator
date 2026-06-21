package dev.frost.obfuscator.jni.patcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Patches selected Java methods into native declarations and prepares metadata
 * for packagers.
 */
public final class JarPatcher {
    private final NativeMethodTransformer transformer = new NativeMethodTransformer();
    private final BridgeMetadataGenerator metadataGenerator = new BridgeMetadataGenerator();

    public PatchResult prepare(PatchPlan plan) throws IOException {
        MethodMappingRegistry registry = MethodMappingRegistry.fromPlans(plan.nativeMethods());
        MethodMappingRegistry transformedRegistry = new MethodMappingRegistry();
        Map<String, byte[]> patchedClasses = new LinkedHashMap<>();

        try (JarFile jarFile = new JarFile(plan.inputJar().toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream inputStream = jarFile.getInputStream(entry)) {
                    ClassNode classNode = readClass(inputStream);
                    var transformedMethods = transformer.transform(classNode, registry, plan.libraryBaseName());
                    if (transformedMethods.isEmpty()) {
                        continue;
                    }
                    transformedMethods.forEach(transformedRegistry::register);
                    patchedClasses.put(entry.getName(), writeClass(classNode));
                }
            }
        }

        return new PatchResult(metadataGenerator.generate(plan, transformedRegistry), patchedClasses, transformedRegistry.asPlans());
    }

    private ClassNode readClass(InputStream inputStream) throws IOException {
        ClassReader reader = new ClassReader(inputStream);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        return classNode;
    }

    private byte[] writeClass(ClassNode classNode) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }
}


