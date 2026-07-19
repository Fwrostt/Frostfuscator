package dev.frost.obfuscator.virtualisation;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;

public final class VirtualizationEligibility {
    private VirtualizationEligibility() {
    }

    public static boolean isEligible(ClassNode owner, MethodNode method, VirtualizationOptions options) {
        if ((method.access & (Opcodes.ACC_NATIVE
                | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_SYNCHRONIZED
                | Opcodes.ACC_BRIDGE)) != 0) {
            return false;
        }
        if (options.skipInitializers() && (method.name.equals("<init>") || method.name.equals("<clinit>"))) {
            return false;
        }
        if (method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        if (method.maxLocals > options.maxLocals() || method.maxStack > options.maxStack()) {
            return false;
        }
        int count = instructionCount(method);
        if (count < options.minInstructions() || count > options.maxInstructions()) {
            return false;
        }
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            return false;
        }
        if (owner.name.startsWith("dev/frost/loader/")) {
            return false;
        }
        return hasSupportedInstructions(method);
    }

    public static int instructionCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasSupportedInstructions(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode || instruction instanceof MultiANewArrayInsnNode) {
                return false;
            }
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.MONITORENTER
                    || opcode == Opcodes.MONITOREXIT
                    || opcode == Opcodes.JSR
                    || opcode == Opcodes.RET) {
                return false;
            }
        }
        return true;
    }
}
