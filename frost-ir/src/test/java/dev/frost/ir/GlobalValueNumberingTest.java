package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.analysis.GlobalValueNumbering;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.pass.CommonSubexpressionEliminationPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class GlobalValueNumberingTest {
    @Test
    void numbersCongruentExpressionsAndEliminatesOnlyDominatingDefinitions() throws Exception {
        MethodNode source = duplicateAdds();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Gvn", source);
        var method = imported.method();
        var adds = method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.ADD)).toList();
        assertEquals(3, adds.size());

        GlobalValueNumbering gvn = GlobalValueNumbering.compute(method);
        assertTrue(gvn.equivalent(adds.get(0).result(), adds.get(1).result()));
        assertEquals(gvn.expression(adds.get(0).result()), gvn.expression(adds.get(1).result()));

        var result = new PassManager().add(new CommonSubexpressionEliminationPass())
                .run(method, new PassContext(new AnalysisManager(), 17L));
        assertTrue(result.changed());
        assertEquals(2, method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.ADD)).count());

        var lowered = new BytecodeMethodLowerer().lower(method, imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        Class<?> type = define("fixture/Gvn", lowered.output().orElseThrow());
        Method twice = type.getDeclaredMethod("twice", int.class);
        assertEquals(12, twice.invoke(null, 5));
    }

    private MethodNode duplicateAdds() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "twice", "(I)I", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
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
