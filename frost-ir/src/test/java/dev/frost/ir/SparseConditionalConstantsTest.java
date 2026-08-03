package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.analysis.ConstantFact;
import dev.frost.ir.analysis.SparseConditionalConstants;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.pass.ConstantFoldingPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

class SparseConditionalConstantsTest {
    @Test
    void propagatesThroughExecutableEdgePhiAndLowersOptimizedMethod() throws Exception {
        MethodNode source = constantDiamond();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/ConstantDiamond", source);
        var method = imported.method();
        var conditional = method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.CONDITIONAL_BRANCH))
                .findFirst().orElseThrow();
        var phi = method.blocks().stream().flatMap(block -> block.phis().stream())
                .filter(candidate -> candidate.result().isUsed()).findFirst().orElseThrow();

        SparseConditionalConstants constants = SparseConditionalConstants.compute(method);
        assertEquals(1, conditional.block().orElseThrow().normalSuccessors().stream()
                .filter(constants::isExecutable).count());
        ConstantFact.Known known = assertInstanceOf(ConstantFact.Known.class, constants.fact(phi.result()));
        assertEquals(7L, assertInstanceOf(IrAttribute.LongValue.class, known.value()).value());

        var pipeline = new PassManager().add(new ConstantFoldingPass())
                .run(method, new PassContext(new AnalysisManager(), 0xF05L));
        assertTrue(pipeline.changed());
        assertFalse(method.blocks().stream().flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.operation().code().equals(CoreOps.CONDITIONAL_BRANCH)));
        assertFalse(method.blocks().stream().flatMap(block -> block.phis().stream()).anyMatch(candidate -> candidate.result().isUsed()));

        var lowered = new BytecodeMethodLowerer().lower(method, imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        Class<?> type = define("fixture/ConstantDiamond", lowered.output().orElseThrow());
        Method choose = type.getDeclaredMethod("choose");
        assertEquals(7, choose.invoke(null));
    }

    @Test
    void divisionByZeroRemainsOverdefinedAndObservable() {
        MethodNode source = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "divide", "()I", null, null);
        source.instructions.add(new InsnNode(Opcodes.ICONST_1));
        source.instructions.add(new InsnNode(Opcodes.ICONST_0));
        source.instructions.add(new InsnNode(Opcodes.IDIV));
        source.instructions.add(new InsnNode(Opcodes.IRETURN));
        source.maxStack = 2;

        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/ZeroDivision", source);
        var division = imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.DIV)).findFirst().orElseThrow();
        SparseConditionalConstants constants = SparseConditionalConstants.compute(imported.method());
        assertInstanceOf(ConstantFact.Overdefined.class, constants.fact(division.result()));

        new PassManager().add(new ConstantFoldingPass()).run(imported.method(),
                new PassContext(new AnalysisManager(), 1L));
        assertTrue(imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction == division));
    }

    @Test
    void obeysJvmIntShiftMaskAndFloatRounding() {
        MethodNode shifts = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shift", "()I", null, null);
        shifts.instructions.add(new InsnNode(Opcodes.ICONST_1));
        shifts.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 32));
        shifts.instructions.add(new InsnNode(Opcodes.ISHL));
        shifts.instructions.add(new InsnNode(Opcodes.IRETURN));
        shifts.maxStack = 2;
        var shiftImport = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Shift", shifts);
        var shift = shiftImport.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.SHL)).findFirst().orElseThrow();
        var shiftKnown = assertInstanceOf(ConstantFact.Known.class,
                SparseConditionalConstants.compute(shiftImport.method()).fact(shift.result()));
        assertEquals(1L, assertInstanceOf(IrAttribute.LongValue.class, shiftKnown.value()).value());

        MethodNode floats = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "add", "()F", null, null);
        floats.instructions.add(new LdcInsnNode(16_777_216f));
        floats.instructions.add(new LdcInsnNode(1f));
        floats.instructions.add(new InsnNode(Opcodes.FADD));
        floats.instructions.add(new InsnNode(Opcodes.FRETURN));
        floats.maxStack = 2;
        var floatImport = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Float", floats);
        var add = floatImport.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.ADD)).findFirst().orElseThrow();
        var floatKnown = assertInstanceOf(ConstantFact.Known.class,
                SparseConditionalConstants.compute(floatImport.method()).fact(add.result()));
        assertEquals(16_777_216d, assertInstanceOf(IrAttribute.DoubleValue.class, floatKnown.value()).value());
    }

    @Test
    void constantAttributesPreserveDistinctNanPayloads() {
        assertNotEquals(IrAttribute.of(Double.longBitsToDouble(0x7ff8000000000001L)),
                IrAttribute.of(Double.longBitsToDouble(0x7ff8000000000002L)));
    }

    private MethodNode constantDiamond() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose", "()I", null, null);
        LabelNode onFalse = new LabelNode();
        LabelNode merge = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, onFalse));
        method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(onFalse);
        method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 9));
        method.instructions.add(merge);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
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
