package dev.frost.obfuscator.jni.patcher;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

/**
 * Marks transformed methods as native while preserving existing access flags.
 */
public final class NativeModifierInjector {
    public void markNative(MethodNode method) {
        method.access |= Opcodes.ACC_NATIVE;
    }
}


