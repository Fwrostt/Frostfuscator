package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.bytecode.BytecodeCfgImporter;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.ImportCapability;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.EdgeKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

class BytecodeCfgImporterTest {
    @Test
    void importsPreciseNormalAndExceptionalCfgWithBidirectionalMapping() {
        MethodNode source = exceptionalMethod();
        var result = new BytecodeCfgImporter(IrContext.standard()).importMethod("fixture/Example", source);

        assertTrue(result.diagnostics().stream().noneMatch(diagnostic -> diagnostic.severity().ordinal() >= 2),
                () -> result.diagnostics().toString());
        assertTrue(result.has(ImportCapability.CONTROL_FLOW));
        assertTrue(result.has(ImportCapability.EXCEPTIONAL_CONTROL_FLOW));
        assertTrue(result.has(ImportCapability.PRESERVED_ASM_SNAPSHOT));
        assertFalse(result.has(ImportCapability.TYPED_STACK_SSA));
        assertTrue(result.method().edges().stream().anyMatch(edge -> edge.kind() == EdgeKind.TRUE));
        assertTrue(result.method().edges().stream().anyMatch(edge -> edge.kind() == EdgeKind.FALSE));
        assertTrue(result.method().edges().stream().anyMatch(edge -> edge.kind().isExceptional()));
        assertEquals(source.tryCatchBlocks.size(), result.method().exceptionRegions().size());
        long executable = java.util.stream.Stream.of(source.instructions.toArray())
                .filter(instruction -> instruction.getOpcode() >= 0).count();
        assertEquals(executable, result.sourceMap().nodeInstructions().size());
    }

    @Test
    void returnsFreshPreservedCloneOnlyWhileIrIsUnchanged() {
        MethodNode source = exceptionalMethod();
        var imported = new BytecodeCfgImporter(IrContext.standard()).importMethod("fixture/Example", source);
        var lowerer = new BytecodeMethodLowerer();
        var unchanged = lowerer.lower(imported.method(), imported);
        assertTrue(unchanged.succeeded(), () -> unchanged.diagnostics().toString());
        assertNotSame(source, unchanged.output().orElseThrow());
        assertEquals(source.instructions.size(), unchanged.output().orElseThrow().instructions.size());
        assertEquals(source.tryCatchBlocks.size(), unchanged.output().orElseThrow().tryCatchBlocks.size());

        imported.method().parameters().getFirst().value().setDebugName("changed");
        var changed = lowerer.lower(imported.method(), imported);
        assertFalse(changed.succeeded());
        assertTrue(changed.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("lowering.requires-ssa")));
    }

    @Test
    void recognizesConstantDynamicAsPotentiallyThrowingPayload() {
        MethodNode source = new MethodNode(Opcodes.ACC_STATIC, "constant", "()Ljava/lang/String;", null, null);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", "value",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
        source.instructions.add(new LdcInsnNode(new ConstantDynamic("secret", "Ljava/lang/String;", bootstrap)));
        source.instructions.add(new InsnNode(Opcodes.ARETURN));
        var imported = new BytecodeCfgImporter(IrContext.standard()).importMethod("fixture/Example", source);
        assertTrue(imported.diagnostics().stream().noneMatch(diagnostic -> diagnostic.severity().ordinal() >= 2));
        assertTrue(imported.sourceMap().nodeInstructions().keySet().stream()
                .filter(LdcInsnNode.class::isInstance)
                .map(imported.sourceMap()::instruction).allMatch(java.util.Optional::isPresent));
    }

    private MethodNode exceptionalMethod() {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "choose", "(I)I", null,
                new String[]{"java/lang/Exception"});
        LabelNode start = new LabelNode();
        LabelNode zero = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, zero));
        method.instructions.add(start);
        method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException",
                "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(end);
        method.instructions.add(zero);
        method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));
        method.maxLocals = 2;
        method.maxStack = 2;
        return method;
    }
}
