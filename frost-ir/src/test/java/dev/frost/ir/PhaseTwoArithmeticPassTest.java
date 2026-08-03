package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.pass.MixedBooleanArithmeticPass;
import dev.frost.ir.pass.NumberObfuscationPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import dev.frost.ir.pass.PolymorphPass;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class PhaseTwoArithmeticPassTest {
    @Test
    void numberObfuscationEntanglesAConstantAcrossARealDominator() throws Exception {
        String owner = "fixture/PhaseTwoNumber";
        MethodNode source = constantBehindDiamond();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod(owner, source);
        var result = new PassManager().add(new NumberObfuscationPass(
                        new NumberObfuscationPass.Options(100, 16, true, true)))
                .run(imported.method(), new PassContext(new AnalysisManager(), 0x10203040L));

        assertTrue(result.changed());
        assertEquals(1L, result.metrics().get(NumberObfuscationPass.ID).get("obfuscated"));
        assertEquals(1L, result.metrics().get(NumberObfuscationPass.ID).get("data_flow_entangled"));
        assertEquals(1L, result.metrics().get(NumberObfuscationPass.ID).get("cross_block"));

        MethodNode lowered = new BytecodeMethodLowerer().lower(imported.method(), imported)
                .output().orElseThrow();
        Class<?> type = define(owner, lowered);
        Method answer = type.getMethod("answer", int.class);
        assertEquals(42, answer.invoke(null, 0));
        assertEquals(42, answer.invoke(null, Integer.MIN_VALUE));
        assertEquals(42, answer.invoke(null, Integer.MAX_VALUE));
    }

    @Test
    void mixedBooleanArithmeticPreservesOverflowAndBuildsLargeSsaDefUseGraphs() throws Exception {
        String owner = "fixture/PhaseTwoMba";
        MethodNode source = arithmetic();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod(owner, source);
        var result = new PassManager().add(new MixedBooleanArithmeticPass(
                        new MixedBooleanArithmeticPass.Options(100, 32, 2_048, 2, 3, 2,
                                Set.of("add", "xor"), false, false, false)))
                .run(imported.method(), new PassContext(new AnalysisManager(), 0x55667788L));

        assertTrue(result.changed());
        assertEquals(3L, result.metrics().get(MixedBooleanArithmeticPass.ID).get("arithmetic"));
        assertTrue(result.metrics().get(MixedBooleanArithmeticPass.ID).get("generated_operations") > 30L);

        MethodNode lowered = new BytecodeMethodLowerer().lower(imported.method(), imported)
                .output().orElseThrow();
        Class<?> type = define(owner, lowered);
        Method mix = type.getMethod("mix", int.class, int.class);
        int[] values = {Integer.MIN_VALUE, -1, 0, 1, 0x55555555, Integer.MAX_VALUE};
        for (int left : values) {
            for (int right : values) {
                assertEquals((left + right) + (left ^ right), mix.invoke(null, left, right));
            }
        }
    }

    @Test
    void mixedBooleanArithmeticStopsWhenTheSsaVerificationBudgetIsReached() {
        String owner = "fixture/PhaseTwoMbaBudget";
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod(owner, arithmetic());
        var result = new PassManager().add(new MixedBooleanArithmeticPass(
                        new MixedBooleanArithmeticPass.Options(100, 32, 64, 2, 3, 2,
                                Set.of("add", "xor"), false, false, false)))
                .run(imported.method(), new PassContext(new AnalysisManager(), 0x11223344L));

        assertTrue(result.changed());
        long rewritten = result.metrics().get(MixedBooleanArithmeticPass.ID).get("arithmetic");
        assertTrue(rewritten > 0 && rewritten < 3,
                "the pass must stop selecting candidates after crossing the SSA budget");
    }

    @Test
    void polymorphSubstitutionsCanSpanBlocksAndRemainExecutable() throws Exception {
        String owner = "fixture/PhaseTwoPolymorph";
        MethodNode source = polymorphicDiamond();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod(owner, source);
        var result = new PassManager().add(new PolymorphPass(
                        new PolymorphPass.Options(100, 32, true)))
                .run(imported.method(), new PassContext(new AnalysisManager(), 0x7f4a7c15L));

        assertTrue(result.changed());
        assertTrue(result.metrics().get(PolymorphPass.ID).get("substituted") >= 2L);
        assertTrue(result.metrics().get(PolymorphPass.ID).get("cross_block") >= 1L);

        MethodNode lowered = new BytecodeMethodLowerer().lower(imported.method(), imported)
                .output().orElseThrow();
        Class<?> type = define(owner, lowered);
        Method compute = type.getMethod("compute", int.class, int.class);
        int[] values = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
        for (int left : values) {
            for (int right : values) {
                assertEquals((left + right) ^ 1, compute.invoke(null, left, right));
            }
        }
    }

    private MethodNode constantBehindDiamond() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "answer", "(I)I", null, null);
        LabelNode zero = new LabelNode();
        LabelNode merge = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, zero));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(zero);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(merge);
        method.instructions.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 42));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        return method;
    }

    private MethodNode arithmetic() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "mix", "(II)I", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IXOR));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 3;
        return method;
    }

    private MethodNode polymorphicDiamond() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "compute", "(II)I", null, null);
        LabelNode zero = new LabelNode();
        LabelNode merge = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, zero));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(zero);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(merge);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IXOR));
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
            Class<?> define() {
                return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }
}
