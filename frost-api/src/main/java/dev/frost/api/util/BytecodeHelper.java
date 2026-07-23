package dev.frost.api.util;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Convenient Bytecode and ASM utilities for Frostfuscator plugin developers.
 */
public final class BytecodeHelper {

    private BytecodeHelper() {}

    /**
     * Finds a method in a ClassNode by name and descriptor.
     */
    public static Optional<MethodNode> findMethod(ClassNode classNode, String name, String descriptor) {
        if (classNode == null || classNode.methods == null) return Optional.empty();
        for (MethodNode mn : classNode.methods) {
            if (mn.name.equals(name) && (descriptor == null || mn.desc.equals(descriptor))) {
                return Optional.of(mn);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds a field in a ClassNode by name.
     */
    public static Optional<FieldNode> findField(ClassNode classNode, String name) {
        if (classNode == null || classNode.fields == null) return Optional.empty();
        for (FieldNode fn : classNode.fields) {
            if (fn.name.equals(name)) {
                return Optional.of(fn);
            }
        }
        return Optional.empty();
    }

    /**
     * Creates a synthetic dummy method with a basic RETURN opcode.
     */
    public static MethodNode createDummyMethod(String name, String descriptor, int access) {
        MethodNode mn = new MethodNode(access | Opcodes.ACC_SYNTHETIC, name, descriptor, null, null);
        InsnList insns = mn.instructions;
        if (descriptor.endsWith("V")) {
            insns.add(new InsnNode(Opcodes.RETURN));
        } else if (descriptor.endsWith("I") || descriptor.endsWith("Z") || descriptor.endsWith("B") || descriptor.endsWith("C") || descriptor.endsWith("S")) {
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new InsnNode(Opcodes.IRETURN));
        } else if (descriptor.endsWith("J")) {
            insns.add(new InsnNode(Opcodes.LCONST_0));
            insns.add(new InsnNode(Opcodes.LRETURN));
        } else if (descriptor.endsWith("F")) {
            insns.add(new InsnNode(Opcodes.FCONST_0));
            insns.add(new InsnNode(Opcodes.FRETURN));
        } else if (descriptor.endsWith("D")) {
            insns.add(new InsnNode(Opcodes.DCONST_0));
            insns.add(new InsnNode(Opcodes.DRETURN));
        } else {
            insns.add(new InsnNode(Opcodes.ACONST_NULL));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }
        return mn;
    }

    /**
     * Creates an instruction node for loading an integer constant (using ICONST_x, BIPUSH, SIPUSH, or LDC).
     */
    public static AbstractInsnNode createIntConstant(int val) {
        if (val >= -1 && val <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + val);
        } else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, val);
        } else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, val);
        } else {
            return new LdcInsnNode(val);
        }
    }

    /**
     * Removes all debug info (line numbers and local variable tables) from a ClassNode.
     */
    public static void clearDebugInfo(ClassNode classNode) {
        if (classNode == null || classNode.methods == null) return;
        classNode.sourceFile = null;
        classNode.sourceDebug = null;
        for (MethodNode mn : classNode.methods) {
            mn.localVariables = null;
            mn.parameters = null;
            if (mn.instructions != null) {
                List<AbstractInsnNode> toRemove = new ArrayList<>();
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof LineNumberNode) {
                        toRemove.add(insn);
                    }
                }
                toRemove.forEach(mn.instructions::remove);
            }
        }
    }

    /**
     * Checks if a ClassNode is synthetic or synthetic-flagged.
     */
    public static boolean isSynthetic(int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }
}
