package dev.frost.obfuscator.jni.patcher;

import org.objectweb.asm.tree.MethodNode;

/**
 * Removes JVM implementation details from a method that will become native.
 */
public final class MethodBodyRemover {
    public void removeBody(MethodNode method) {
        method.instructions.clear();
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.clear();
        }
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        if (method.visibleLocalVariableAnnotations != null) {
            method.visibleLocalVariableAnnotations.clear();
        }
        if (method.invisibleLocalVariableAnnotations != null) {
            method.invisibleLocalVariableAnnotations.clear();
        }
        method.maxStack = 0;
        method.maxLocals = 0;
    }
}


