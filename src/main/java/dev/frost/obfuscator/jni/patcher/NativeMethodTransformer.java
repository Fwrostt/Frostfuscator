package dev.frost.obfuscator.jni.patcher;

import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts selected methods in a class to JNI native method declarations.
 */
public final class NativeMethodTransformer {
    private final MethodBodyRemover bodyRemover = new MethodBodyRemover();
    private final NativeModifierInjector nativeModifierInjector = new NativeModifierInjector();
    private final LibraryLoaderInjector loaderInjector = new LibraryLoaderInjector();

    public List<NativeMethodPlan> transform(ClassNode classNode, MethodMappingRegistry registry, String libraryBaseName) {
        List<NativeMethodPlan> transformed = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (!shouldTransform(classNode.name, method, registry)) {
                continue;
            }
            bodyRemover.removeBody(method);
            nativeModifierInjector.markNative(method);
            String nativeSymbol = registry.find(classNode.name, method.name, method.desc).orElseThrow();
            transformed.add(new NativeMethodPlan(classNode.name, method.name, method.desc, nativeSymbol));
            Logger.info("[PATCHER] Converted: {}::{}{} to native method.",
                    classNode.name, method.name, method.desc);
        }
        if (!transformed.isEmpty()) {
            loaderInjector.inject(classNode, libraryBaseName);
        }
        return transformed;
    }

    private boolean shouldTransform(String ownerInternalName, MethodNode method, MethodMappingRegistry registry) {
        if (!registry.contains(ownerInternalName, method.name, method.desc)) {
            return false;
        }
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
            return false;
        }
        int hardBlocked = Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE;
        if ((method.access & hardBlocked) != 0) {
            return false;
        }
        return (method.access & Opcodes.ACC_SYNTHETIC) == 0 || method.name.startsWith("lambda$");
    }
}


