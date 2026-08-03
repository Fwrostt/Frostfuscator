package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.bytecode.ImportCapability;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.verify.IrValidator;
import dev.frost.ir.verify.ValidationProfile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

class BytecodeSsaImporterTest {
    @Test
    void liftsDiamondStackMergeIntoTypedEdgePhis() {
        MethodNode source = diamond();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Ssa", source);

        assertNoErrors(imported);
        assertTrue(imported.has(ImportCapability.TYPED_STACK_SSA));
        assertTrue(imported.has(ImportCapability.FRAME_STATES));
        assertTrue(imported.frames().isPresent());
        assertFalse(imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.operation().code().equals(CoreOps.OPAQUE_BYTECODE)
                        || instruction.operation().code().equals(CoreOps.OPAQUE_PURE_BYTECODE)
                        || instruction.operation().code().equals(CoreOps.OPAQUE_TERMINATOR)));
        assertTrue(imported.method().blocks().stream().flatMap(block -> block.phis().stream())
                .anyMatch(phi -> phi.inputs().size() == 2));
        assertTrue(new IrValidator().validate(imported.method(), ValidationProfile.STRICT).isValid());
    }

    @Test
    void simulatesCategoryTwoLocalsConversionsAndArithmetic() {
        MethodNode source = new MethodNode(Opcodes.ACC_STATIC, "math", "(JI)J", null, null);
        source.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        source.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        source.instructions.add(new InsnNode(Opcodes.I2L));
        source.instructions.add(new InsnNode(Opcodes.LADD));
        source.instructions.add(new VarInsnNode(Opcodes.LSTORE, 3));
        source.instructions.add(new VarInsnNode(Opcodes.LLOAD, 3));
        source.instructions.add(new InsnNode(Opcodes.LRETURN));
        source.maxLocals = 5;
        source.maxStack = 4;

        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Ssa", source);
        assertNoErrors(imported);
        assertTrue(imported.has(ImportCapability.TYPED_STACK_SSA));
        assertTrue(imported.method().blocks().getFirst().instructions().stream()
                .anyMatch(instruction -> instruction.operation().code().equals(CoreOps.CONVERT)));
        assertTrue(imported.method().blocks().getFirst().instructions().stream()
                .anyMatch(instruction -> instruction.operation().code().equals(CoreOps.ADD)
                        && instruction.result().type() == dev.frost.ir.type.PrimitiveType.LONG));
    }

    @Test
    void modelsExceptionObjectAsEdgeValueAndHandlerPhi() {
        MethodNode source = arrayCatch();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Ssa", source);

        assertNoErrors(imported);
        assertTrue(imported.has(ImportCapability.TYPED_STACK_SSA));
        var exceptional = imported.method().edges().stream().filter(edge -> edge.kind().isExceptional()).toList();
        assertEquals(1, exceptional.size());
        assertEquals(1, exceptional.getFirst().values().size());
        assertEquals("exception", exceptional.getFirst().values().getFirst().role());
        assertTrue(exceptional.getFirst().target().phis().stream()
                .anyMatch(phi -> phi.inputs().get(exceptional.getFirst()) == exceptional.getFirst().values().getFirst().result()));
    }

    @Test
    void liftsConstantDynamicWithLinkageEffects() {
        MethodNode source = new MethodNode(Opcodes.ACC_STATIC, "constant", "()Ljava/lang/String;", null, null);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", "value",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
        source.instructions.add(new LdcInsnNode(new ConstantDynamic("secret", "Ljava/lang/String;", bootstrap)));
        source.instructions.add(new InsnNode(Opcodes.ARETURN));
        source.maxLocals = 0;
        source.maxStack = 1;

        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Ssa", source);
        assertNoErrors(imported);
        assertTrue(imported.has(ImportCapability.TYPED_STACK_SSA));
        assertTrue(imported.method().blocks().getFirst().instructions().stream()
                .anyMatch(instruction -> instruction.operation().code().equals(CoreOps.CONSTANT_DYNAMIC)));
    }

    @Test
    void tracksUninitializedThisThroughConstructorInvocation() {
        MethodNode source = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        VarInsnNode loadThis = new VarInsnNode(Opcodes.ALOAD, 0);
        MethodInsnNode initialize = new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        source.instructions.add(loadThis);
        source.instructions.add(initialize);
        source.instructions.add(new InsnNode(Opcodes.RETURN));
        source.maxLocals = 1;
        source.maxStack = 1;

        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Ssa", source);
        assertNoErrors(imported);
        assertTrue(imported.has(ImportCapability.TYPED_STACK_SSA));
        assertTrue(imported.method().blocks().getFirst().instructions().stream()
                .anyMatch(instruction -> instruction.operation().code().equals(CoreOps.INITIALIZE)));
        var after = imported.frames().orElseThrow().after(initialize).orElseThrow();
        assertTrue(after.locals().getFirst().type() instanceof dev.frost.ir.type.ReferenceType reference
                && reference.internalName().equals("fixture/Ssa"));
    }

    @Test
    void liftsInvokeDynamicAndPreservesBootstrapPayload() {
        MethodNode source = new MethodNode(Opcodes.ACC_STATIC, "factory", "()Ljava/lang/Runnable;", null, null);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", "link",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;", bootstrap, "payload");
        source.instructions.add(indy);
        source.instructions.add(new InsnNode(Opcodes.ARETURN));
        source.maxLocals = 0;
        source.maxStack = 1;

        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Ssa", source);
        assertNoErrors(imported);
        var operation = imported.sourceMap().instruction(indy).orElseThrow();
        assertEquals(CoreOps.INVOKE_DYNAMIC, operation.operation().code());
        assertTrue(operation.operation().attributes().containsKey("bootstrap"));
        assertTrue(operation.operation().attributes().containsKey("bootstrap_args"));
    }

    @Test
    void createsParallelSwitchEdgesAndThreeWayStackPhi() {
        MethodNode source = lookupSwitch();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Ssa", source);

        assertNoErrors(imported);
        assertEquals(2, imported.method().edges().stream().filter(edge -> edge.kind() == EdgeKind.SWITCH_CASE).count());
        assertEquals(1, imported.method().edges().stream().filter(edge -> edge.kind() == EdgeKind.SWITCH_DEFAULT).count());
        assertTrue(imported.method().blocks().stream().flatMap(block -> block.phis().stream())
                .anyMatch(phi -> phi.inputs().size() == 3));
    }

    @Test
    void modelsCategoryTwoStackPermutationWithoutInventingDefinitions() {
        MethodNode source = new MethodNode(Opcodes.ACC_STATIC, "duplicate", "()J", null, null);
        InsnNode duplicate = new InsnNode(Opcodes.DUP2);
        source.instructions.add(new InsnNode(Opcodes.LCONST_0));
        source.instructions.add(duplicate);
        source.instructions.add(new InsnNode(Opcodes.POP2));
        source.instructions.add(new InsnNode(Opcodes.LRETURN));
        source.maxLocals = 0;
        source.maxStack = 4;

        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Ssa", source);
        assertNoErrors(imported);
        var permutation = imported.sourceMap().instruction(duplicate).orElseThrow();
        assertEquals(CoreOps.STACK_PERMUTE, permutation.operation().code());
        assertEquals(1, permutation.operands().size());
        assertTrue(permutation.operands().getFirst().type().isCategory2());
    }

    private MethodNode diamond() {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "choose", "(I)I", null, null);
        LabelNode onFalse = new LabelNode();
        LabelNode merge = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, onFalse));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(onFalse);
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(merge);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        return method;
    }

    private MethodNode arrayCatch() {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "first", "([I)I", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IALOAD));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));
        method.maxLocals = 1;
        method.maxStack = 2;
        return method;
    }

    private MethodNode lookupSwitch() {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "select", "(I)I", null, null);
        LabelNode one = new LabelNode();
        LabelNode two = new LabelNode();
        LabelNode fallback = new LabelNode();
        LabelNode merge = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new LookupSwitchInsnNode(fallback, new int[]{1, 2}, new LabelNode[]{one, two}));
        method.instructions.add(one);
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(two);
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(fallback);
        method.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        method.instructions.add(merge);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        return method;
    }

    private void assertNoErrors(dev.frost.ir.bytecode.BytecodeImportResult imported) {
        assertTrue(imported.diagnostics().stream().noneMatch(diagnostic -> diagnostic.severity().ordinal() >= 2),
                () -> imported.diagnostics().toString());
    }
}
