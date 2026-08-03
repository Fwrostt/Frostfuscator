package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodSignature;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.pass.ConstantFoldingPass;
import dev.frost.ir.pass.CriticalEdgeSplittingPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import dev.frost.ir.pass.UnreachableBlockEliminationPass;
import dev.frost.ir.type.MethodType;
import dev.frost.ir.type.PrimitiveType;
import java.util.List;
import org.junit.jupiter.api.Test;

class CfgMutationTest {
    @Test
    void erasedDefinitionsAreNoLongerOwnedEntities() {
        IrMethod method = new IrMethod(IrContext.standard(), new MethodSignature("fixture/Lifecycle", "run",
                new MethodType(List.of(), PrimitiveType.VOID), 0x0008, null, List.of()));
        BasicBlock entry = method.createBlock("entry");
        var dead = FrostIrTestFixtures.constant(method, 7);
        entry.append(dead);
        entry.append(method.createInstruction(CoreOps.RETURN, List.of(), List.of()));
        var value = dead.result();
        entry.remove(dead);
        assertThrows(IllegalArgumentException.class, () -> method.requireOwned(dead));
        assertThrows(IllegalArgumentException.class, () -> method.requireOwned(value));
    }

    @Test
    void removesBlocksMadeUnreachableByConstantBranchPruning() {
        var imported = new dev.frost.ir.bytecode.BytecodeSsaImporter(IrContext.standard())
                .importMethod("fixture/DeadBranch", constantDiamond());
        int before = imported.method().blocks().size();
        new PassManager().add(new ConstantFoldingPass()).add(new UnreachableBlockEliminationPass())
                .run(imported.method(), new PassContext(new AnalysisManager(), 1));
        assertEquals(before - 1, imported.method().blocks().size());
    }

    @Test
    void splitsCriticalEdgesAndRekeysPhiInputs() {
        IrMethod method = new IrMethod(IrContext.standard(), new MethodSignature("fixture/Critical", "run",
                new MethodType(List.of(), PrimitiveType.INT), 0x0008, null, List.of()));
        BasicBlock entry = method.createBlock("entry"), onFalse = method.createBlock("on_false"),
                merge = method.createBlock("merge");
        var condition = FrostIrTestFixtures.constant(method, 1);
        var direct = FrostIrTestFixtures.constant(method, 7);
        entry.append(condition); entry.append(direct);
        entry.append(method.createInstruction(CoreOps.CONDITIONAL_BRANCH, List.of(condition.result()), List.of()));
        ControlEdge critical = method.connect(entry, merge, EdgeKind.TRUE);
        method.connect(entry, onFalse, EdgeKind.FALSE);
        var alternate = FrostIrTestFixtures.constant(method, 9);
        onFalse.append(alternate);
        onFalse.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
        ControlEdge falseMerge = method.connect(onFalse, merge, EdgeKind.NORMAL);
        PhiNode phi = merge.addPhi(PrimitiveType.INT, "result");
        phi.putInput(critical, direct.result()); phi.putInput(falseMerge, alternate.result());
        merge.append(method.createInstruction(CoreOps.RETURN, List.of(phi.result()), List.of()));

        new PassManager().add(new CriticalEdgeSplittingPass())
                .run(method, new PassContext(new AnalysisManager(), 2));
        assertEquals(4, method.blocks().size());
        assertThrows(IllegalArgumentException.class, () -> method.requireOwned(critical));
        assertEquals(2, phi.inputs().size());
        assertTrue(phi.inputs().keySet().stream().noneMatch(edge -> edge == critical));
    }

    private org.objectweb.asm.tree.MethodNode constantDiamond() {
        var method = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC
                | org.objectweb.asm.Opcodes.ACC_STATIC, "choose", "()I", null, null);
        var onFalse = new org.objectweb.asm.tree.LabelNode();
        var merge = new org.objectweb.asm.tree.LabelNode();
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.IFEQ, onFalse));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_2));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.GOTO, merge));
        method.instructions.add(onFalse);
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_3));
        method.instructions.add(merge);
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN));
        method.maxStack = 1;
        return method;
    }
}
