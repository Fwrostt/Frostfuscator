package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.bytecode.BytecodeClassImporter;
import dev.frost.ir.bytecode.BytecodeClassLowerer;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.pass.CommonSubexpressionEliminationPass;
import dev.frost.ir.pass.ConstantFoldingPass;
import dev.frost.ir.pass.DeadCodeEliminationPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class BytecodeDifferentialFuzzTest {
    private static final int[] OPERATIONS = {
            Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IXOR,
            Opcodes.IAND, Opcodes.IOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR
    };

    @Test
    void randomizedIntegerProgramsRemainEquivalentAfterIrOptimizationAndLowering() throws Exception {
        for (int seed = 0; seed < 24; seed++) {
            int currentSeed = seed;
            String internalName = "fixture/Fuzz" + seed;
            byte[] originalBytes = program(internalName, seed);
            var imported = new BytecodeClassImporter(IrContext.standard()).importClass(originalBytes);
            var compute = imported.methods().entrySet().stream()
                    .filter(entry -> entry.getKey().name().equals("compute")).map(Map.Entry::getValue)
                    .findFirst().orElseThrow();
            var pipeline = new PassManager().add(new ConstantFoldingPass())
                    .add(new CommonSubexpressionEliminationPass()).add(new DeadCodeEliminationPass());
            pipeline.run(compute.method(), new PassContext(new AnalysisManager(), seed));
            compute.method().parameters().getFirst().value().setDebugName("a" + seed);

            var lowered = new BytecodeClassLowerer().lower(imported);
            assertTrue(lowered.succeeded(), () -> "seed=" + currentSeed + " " + lowered.diagnostics());
            Class<?> original = define(internalName, originalBytes);
            Class<?> rewritten = define(internalName, lowered.output().orElseThrow());
            Method expected = original.getMethod("compute", int.class, int.class);
            Method actual = rewritten.getMethod("compute", int.class, int.class);
            Random inputs = new Random(0xC0FFEE00L + seed);
            for (int sample = 0; sample < 32; sample++) {
                int left = inputs.nextInt();
                int right = inputs.nextInt();
                assertEquals(expected.invoke(null, left, right), actual.invoke(null, left, right),
                        "seed=" + seed + ", sample=" + sample);
            }
        }
    }

    private byte[] program(String internalName, int seed) {
        Random random = new Random(seed * 0x9E3779B97F4A7C15L);
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = internalName;
        node.superName = "java/lang/Object";
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);

        MethodNode compute = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "compute", "(II)I", null, null);
        compute.instructions.add(new InsnNode(Opcodes.NOP));
        compute.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        compute.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        int steps = 4 + random.nextInt(8);
        for (int index = 0; index < steps; index++) {
            compute.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
            int operand = random.nextInt(3);
            if (operand < 2) compute.instructions.add(new VarInsnNode(Opcodes.ILOAD, operand));
            else compute.instructions.add(new IntInsnNode(Opcodes.BIPUSH, random.nextInt(65) - 32));
            compute.instructions.add(new InsnNode(OPERATIONS[random.nextInt(OPERATIONS.length)]));
            compute.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        }
        LabelNode unchanged = new LabelNode();
        compute.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        compute.instructions.add(new JumpInsnNode(Opcodes.IFEQ, unchanged));
        compute.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        compute.instructions.add(new InsnNode(Opcodes.ICONST_1));
        compute.instructions.add(new InsnNode(Opcodes.IXOR));
        compute.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        compute.instructions.add(unchanged);
        compute.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        compute.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(compute);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private Class<?> define(String internalName, byte[] bytes) {
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }
}
