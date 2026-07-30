package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CondyIndirectionTest {

    @Test
    void testCondyIndirectionReplacesLdcWithConstantDynamic() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/CondyApp";
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.version = Opcodes.V11;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getMessage", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode("Hello Condy"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        CondyIndirectionTransformer transformer = new CondyIndirectionTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        transformer.transform(pool, new MappingCollector(), config);

        // Verify LdcInsnNode holds ConstantDynamic
        AbstractInsnNode firstInsn = method.instructions.getFirst();
        assertTrue(firstInsn instanceof LdcInsnNode);
        LdcInsnNode ldc = (LdcInsnNode) firstInsn;
        assertTrue(ldc.cst instanceof ConstantDynamic);

        // Verify bootstrap method was generated
        boolean hasBootstrap = classNode.methods.stream().anyMatch(m -> m.name.equals("__frost$condy$bootstrap"));
        assertTrue(hasBootstrap, "Condy bootstrap method should be generated");
    }

    @Test
    void primitiveConstantsRetainPrimitiveJvmStackTypes() throws Exception {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/PrimitiveCondy";
        classNode.superName = "java/lang/Object";
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.version = Opcodes.V17;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "values", "()I", null, null);
        method.instructions.add(new LdcInsnNode(7));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new LdcInsnNode(8L));
        method.instructions.add(new InsnNode(Opcodes.POP2));
        method.instructions.add(new LdcInsnNode(9.0f));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new LdcInsnNode(10.0d));
        method.instructions.add(new InsnNode(Opcodes.POP2));
        method.instructions.add(new LdcInsnNode(11));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        new CondyIndirectionTransformer().transform(pool, new MappingCollector(), config);

        List<String> descriptors = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic condy) {
                descriptors.add(condy.getDescriptor());
            }
        }
        assertEquals(List.of("I", "J", "F", "D", "I"), descriptors);
        assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(classNode.name, method));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        assertDoesNotThrow(() -> {
            classNode.accept(writer);
            new ClassReader(writer.toByteArray());
        });
    }

    @Test
    void methodAndFieldReferencesUseJdkConstantBootstrapsAndRemainExecutable() throws Exception {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/MemberCondy";
        classNode.superName = "java/lang/Object";
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.version = Opcodes.V11;
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "VALUE", "I", null, 7));
        classNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "wideValue", "J", null, null));

        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(constructor);

        MethodNode increment = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "increment", "(I)I", null, null);
        increment.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        increment.instructions.add(new InsnNode(Opcodes.ICONST_1));
        increment.instructions.add(new InsnNode(Opcodes.IADD));
        increment.instructions.add(new InsnNode(Opcodes.IRETURN));
        classNode.methods.add(increment);

        MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
        run.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, "VALUE", "I"));
        run.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                classNode.name, "increment", "(I)I", false));
        run.instructions.add(new InsnNode(Opcodes.IRETURN));
        classNode.methods.add(run);

        MethodNode roundTrip = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "roundTrip", "()J", null, null);
        roundTrip.instructions.add(new TypeInsnNode(Opcodes.NEW, classNode.name));
        roundTrip.instructions.add(new InsnNode(Opcodes.DUP));
        roundTrip.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                classNode.name, "<init>", "()V", false));
        roundTrip.instructions.add(new InsnNode(Opcodes.DUP));
        roundTrip.instructions.add(new LdcInsnNode(9L));
        roundTrip.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                classNode.name, "wideValue", "J"));
        roundTrip.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                classNode.name, "wideValue", "J"));
        roundTrip.instructions.add(new InsnNode(Opcodes.LRETURN));
        classNode.methods.add(roundTrip);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of(
                "probability", 100,
                "constants", false,
                "method-handles", true,
                "var-handles", true));

        new CondyIndirectionTransformer().transform(pool, new MappingCollector(), config);

        List<String> bootstrapNames = new ArrayList<>();
        boolean invokesMethodHandle = false;
        boolean invokesVarHandle = false;
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic condy) {
                bootstrapNames.add(condy.getBootstrapMethod().getName());
            } else if (instruction instanceof MethodInsnNode invocation) {
                invokesMethodHandle |= invocation.owner.equals("java/lang/invoke/MethodHandle")
                        && invocation.name.equals("invokeExact");
                invokesVarHandle |= invocation.owner.equals("java/lang/invoke/VarHandle")
                        && invocation.name.equals("get");
            }
        }
        assertTrue(bootstrapNames.contains("staticFieldVarHandle"));
        assertTrue(bootstrapNames.contains("invoke"));
        assertTrue(invokesMethodHandle);
        assertTrue(invokesVarHandle);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        byte[] bytecode = writer.toByteArray();
        Class<?> generated = new ClassLoader() {
            Class<?> define() {
                return defineClass("com.example.MemberCondy", bytecode, 0, bytecode.length);
            }
        }.define();
        assertEquals(8, generated.getMethod("run").invoke(null));
        assertEquals(9L, generated.getMethod("roundTrip").invoke(null));
    }
}
