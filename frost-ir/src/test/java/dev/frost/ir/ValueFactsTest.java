package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frost.ir.analysis.IntegerRange;
import dev.frost.ir.analysis.IntegerRangeAnalysis;
import dev.frost.ir.analysis.Nullness;
import dev.frost.ir.analysis.NullnessAnalysis;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.core.IrContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class ValueFactsTest {
    @Test
    void derivesIntegerIntervalsAcrossEdgePhis() {
        var diamond = FrostIrTestFixtures.diamond();
        IntegerRangeAnalysis ranges = IntegerRangeAnalysis.compute(diamond.method());
        assertEquals(new IntegerRange(1, 2), ranges.range(diamond.result().result()).orElseThrow());
    }

    @Test
    void joinsNullAndFreshAllocationAsMaybeNull() {
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Nullness", nullableObject());
        var returned = imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(dev.frost.ir.model.CoreOps.RETURN))
                .findFirst().orElseThrow().operands().getFirst();
        assertEquals(Nullness.MAYBE_NULL, NullnessAnalysis.compute(imported.method()).fact(returned).orElseThrow());
    }

    private MethodNode nullableObject() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "choose", "(I)Ljava/lang/Object;", null, null);
        LabelNode onNull = new LabelNode();
        LabelNode merge = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, onNull));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(onNull);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(merge);
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 1;
        method.maxStack = 2;
        return method;
    }
}
