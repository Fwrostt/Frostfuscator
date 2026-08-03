package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodSignature;
import dev.frost.ir.pass.CopyPropagationPass;
import dev.frost.ir.pass.DeadCodeEliminationPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import dev.frost.ir.transform.IrGraphInliner;
import dev.frost.ir.type.MethodType;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.verify.IrValidator;
import dev.frost.ir.verify.ValidationProfile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class PhaseOneOptimizationTest {
    @Test
    void markSweepDceRemovesDeadPhiCycles() {
        IrMethod method = new IrMethod(IrContext.standard(), new MethodSignature("fixture/DeadPhi", "run",
                new MethodType(List.of(), PrimitiveType.VOID), Opcodes.ACC_STATIC, null, List.of()));
        var entry = method.createBlock("entry");
        var deadLoop = method.createBlock("dead_loop");
        entry.append(method.createInstruction(CoreOps.RETURN, List.of(), List.of()));
        deadLoop.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
        var backedge = method.connect(deadLoop, deadLoop, EdgeKind.NORMAL);
        var deadPhi = deadLoop.addPhi(PrimitiveType.INT, "dead_cycle");
        deadPhi.putInput(backedge, deadPhi.result());

        var result = new PassManager().add(new DeadCodeEliminationPass())
                .run(method, new PassContext(new AnalysisManager(), 1L));

        assertTrue(result.changed());
        assertTrue(deadLoop.phis().isEmpty());
        assertTrue(new IrValidator().validate(method, ValidationProfile.STRICT).isValid());
    }

    @Test
    void propagatesExplicitSsaCopies() {
        IrMethod method = new IrMethod(IrContext.standard(), new MethodSignature("fixture/Copy", "identity",
                new MethodType(List.of(PrimitiveType.INT), PrimitiveType.INT), Opcodes.ACC_STATIC, null, List.of()));
        var input = method.addParameter("input", PrimitiveType.INT).value();
        var entry = method.createBlock("entry");
        var copy = method.createInstruction(CoreOps.COPY, List.of(input), List.of(PrimitiveType.INT));
        entry.append(copy);
        var ret = method.createInstruction(CoreOps.RETURN, List.of(copy.result()), List.of());
        entry.append(ret);

        new PassManager().add(new CopyPropagationPass())
                .run(method, new PassContext(new AnalysisManager(), 2L));

        assertFalse(method.entity(copy.id()).isPresent());
        assertEquals(input, ret.operands().getFirst());
    }

    @Test
    void splicesBranchingArgumentTakingCalleeAndExecutes() throws Exception {
        String owner = "fixture/IrInline";
        IrContext context = IrContext.standard();
        MethodNode calleeBytecode = branchingCallee(owner);
        MethodNode callerBytecode = caller(owner);
        var callee = new BytecodeSsaImporter(context).importMethod(owner, calleeBytecode);
        var caller = new BytecodeSsaImporter(context).importMethod(owner, callerBytecode);
        var invoke = caller.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.INVOKE)).findFirst().orElseThrow();

        var result = new IrGraphInliner().inline(caller.method(), invoke, callee.method(), "test_inline");

        assertTrue(result.clonedBlocks() >= 3);
        assertTrue(caller.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .noneMatch(instruction -> instruction.operation().code().equals(CoreOps.INVOKE)));
        var lowered = new BytecodeMethodLowerer().lower(caller.method(), caller);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        Class<?> type = define(owner, lowered.output().orElseThrow());
        var method = type.getMethod("call", int.class, int.class);
        assertEquals(14, method.invoke(null, 3, 4));
        assertEquals(6, method.invoke(null, 0, 4));
    }

    private MethodNode branchingCallee(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "choose", "(II)I", null, null);
        LabelNode onFalse = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, onFalse));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(onFalse);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.ISUB));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 2;
        return method;
    }

    private MethodNode caller(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "call", "(II)I", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "choose", "(II)I", false));
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new InsnNode(Opcodes.IMUL));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 2;
        return method;
    }

    private Class<?> define(String internalName, MethodNode method) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = internalName;
        node.superName = "java/lang/Object";
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }
}
