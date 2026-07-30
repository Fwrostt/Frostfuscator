package dev.frost.obfuscator.virtualisation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualizationEligibilityTest {

    private static final VirtualizationOptions OPTIONS = new VirtualizationOptions(
            1, 0, 100, 100, true, false, 32, 32);

    @Test
    void acceptsLongAndDoubleValuesAtStackManipulationInstructions() {
        MethodNode longMethod = wideDupAndAdd("()J", Opcodes.LCONST_1, Opcodes.LADD, Opcodes.LRETURN);
        assertTrue(eligible(longMethod));
        assertTrue(eligible(wideDupAndAdd("()D", Opcodes.DCONST_1, Opcodes.DADD, Opcodes.DRETURN)));
        new BytecodeTranslator(longMethod, new OpcodeTable(new Random(1))).translate();
    }

    @Test
    void keepsOrdinaryWideArithmeticEligible() {
        MethodNode method = method("()J");
        method.instructions.add(new InsnNode(Opcodes.LCONST_1));
        method.instructions.add(new InsnNode(Opcodes.LCONST_1));
        method.instructions.add(new InsnNode(Opcodes.LADD));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        method.maxStack = 4;

        assertTrue(eligible(method));
        assertTrue(BytecodeTranslator.isEligible(method));
    }

    @Test
    void allowsSingleSlotSwapEvenWhenMethodHasWideArguments() {
        MethodNode method = method("(J)I");
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new InsnNode(Opcodes.SWAP));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 2;

        assertTrue(eligible(method));
    }

    private static MethodNode wideDupAndAdd(String descriptor, int constant, int add, int returnOpcode) {
        MethodNode method = method(descriptor);
        method.instructions.add(new InsnNode(constant));
        method.instructions.add(new InsnNode(Opcodes.DUP2));
        method.instructions.add(new InsnNode(add));
        method.instructions.add(new InsnNode(returnOpcode));
        method.maxStack = 4;
        return method;
    }

    private static MethodNode method(String descriptor) {
        return new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "candidate", descriptor, null, null);
    }

    private static boolean eligible(MethodNode method) {
        ClassNode owner = new ClassNode();
        owner.name = "sample/Virtualized";
        owner.methods.add(method);
        return VirtualizationEligibility.isEligible(owner, method, OPTIONS);
    }
}
