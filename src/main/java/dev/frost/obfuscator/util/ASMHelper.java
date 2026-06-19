package dev.frost.obfuscator.util;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Shared ASM helpers used by bytecode-modifying transformers.
 */
public final class ASMHelper {

    private ASMHelper() {
    }

    /**
     * Allocate a fresh local variable slot above the current maxLocals.
     * Returns the slot and updates maxLocals if needed.
     */
    public static int allocateLocal(MethodNode method, int size) {
        int slot = method.maxLocals;
        method.maxLocals += size;
        return slot;
    }

    /**
     * Free a previously allocated local by reducing maxLocals if it was the highest slot.
     * Safe to call even if other slots were allocated after it.
     */
    public static void freeLocal(MethodNode method, int slot, int size) {
        if (slot + size == method.maxLocals) {
            method.maxLocals -= size;
        }
    }

    /**
     * Compute the next available local slot by inspecting all variable instructions
     * and existing local variable nodes. More accurate than maxLocals alone when
     * transformers have not kept maxLocals in sync.
     */
    public static int nextFreeLocal(MethodNode method) {
        int max = method.maxLocals;
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            int opcode = insn.getOpcode();
            if (opcode >= 21 && opcode <= 25) { // ILOAD..ALOAD
                max = Math.max(max, ((org.objectweb.asm.tree.VarInsnNode) insn).var +
                        (opcode == 22 || opcode == 24 ? 2 : 1)); // LLOAD/DLOAD use 2 slots
            } else if (opcode >= 54 && opcode <= 58) { // ISTORE..ASTORE
                max = Math.max(max, ((org.objectweb.asm.tree.VarInsnNode) insn).var +
                        (opcode == 55 || opcode == 57 ? 2 : 1));
            } else if (insn instanceof org.objectweb.asm.tree.IincInsnNode iinc) {
                max = Math.max(max, iinc.var + 1);
            }
            insn = insn.getNext();
        }
        return max;
    }

    /**
     * Find all instructions of the given opcodes in a method.
     */
    public static Set<AbstractInsnNode> findInsns(MethodNode method, int... opcodes) {
        Set<Integer> set = new HashSet<>();
        for (int op : opcodes) set.add(op);
        Set<AbstractInsnNode> result = new HashSet<>();
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            if (set.contains(insn.getOpcode())) result.add(insn);
            insn = insn.getNext();
        }
        return result;
    }

    /**
     * Clone an instruction list.
     */
    public static InsnList clone(InsnList list) {
        InsnList copy = new InsnList();
        AbstractInsnNode insn = list.getFirst();
        while (insn != null) {
            copy.add(insn.clone(null));
            insn = insn.getNext();
        }
        return copy;
    }

    /**
     * Basic analyzer wrapper that returns the produced frames, or null on failure.
     */
    public static Frame<BasicValue>[] analyzeSafe(String owner, MethodNode method) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            return analyzer.analyze(owner, method);
        } catch (AnalyzerException e) {
            return null;
        }
    }
}
