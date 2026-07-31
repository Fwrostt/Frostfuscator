package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

class CondyIndirectionTest {

    @Test
    void testCondyIndirectionReplacesLdcWithEncryptedNestedConstantDynamic() throws Exception {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/CondyApp";
        classNode.superName = "java/lang/Object";
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.version = Opcodes.V11;

        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(constructor);

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

        ConstantDynamic dynamic = (ConstantDynamic) ldc.cst;
        assertEquals("Ljava/lang/String;", dynamic.getDescriptor());
        assertNotEquals(classNode.name, dynamic.getBootstrapMethod().getOwner());
        assertTrue(bootstrapArguments(dynamic).stream().anyMatch(ConstantDynamic.class::isInstance),
                "The encrypted value must chain through a nested key Condy");
        assertFalse(bootstrapArguments(dynamic).contains("Hello Condy"),
                "Plaintext must not remain in bootstrap arguments");
        assertEquals(2, pool.size(), "A relocated bootstrap carrier should be injected once");

        Class<?> generated = load(pool, classNode.name);
        assertEquals("Hello Condy", generated.getMethod("getMessage").invoke(
                generated.getConstructor().newInstance()));
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
                bootstrapNames.add(condy.getBootstrapMethod().getOwner());
                assertTrue(bootstrapArguments(condy).stream().anyMatch(ConstantDynamic.class::isInstance));
            } else if (instruction instanceof MethodInsnNode invocation) {
                invokesMethodHandle |= invocation.owner.equals("java/lang/invoke/MethodHandle")
                        && invocation.name.equals("invokeExact");
                invokesVarHandle |= invocation.owner.equals("java/lang/invoke/VarHandle")
                        && invocation.name.equals("get");
            }
        }
        assertFalse(bootstrapNames.isEmpty());
        assertEquals(1, bootstrapNames.stream().distinct().count(),
                "Member references should share one relocated cipher bootstrap carrier");
        assertTrue(invokesMethodHandle);
        assertTrue(invokesVarHandle);

        Class<?> generated = load(pool, classNode.name);
        assertEquals(8, generated.getMethod("run").invoke(null));
        assertEquals(9L, generated.getMethod("roundTrip").invoke(null));
    }

    @Test
    void immediateNumbersClassLiteralsMethodTypesAndHandlesResolveWithoutPlainMetadata() throws Exception {
        ClassNode node = new ClassNode();
        node.name = "com/example/AllConstants";
        node.superName = "java/lang/Object";
        node.access = Opcodes.ACC_PUBLIC;
        node.version = Opcodes.V11;

        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);

        MethodNode number = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "number", "()I", null, null);
        number.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 42));
        number.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(number);

        MethodNode type = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "type", "()Ljava/lang/Class;", null, null);
        type.instructions.add(new LdcInsnNode(Type.getType("Ljava/lang/String;")));
        type.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(type);

        MethodNode methodType = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "methodType", "()Ljava/lang/invoke/MethodType;", null, null);
        methodType.instructions.add(new LdcInsnNode(Type.getMethodType("(I)Ljava/lang/String;")));
        methodType.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(methodType);

        MethodNode target = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "target", "(I)I", null, null);
        target.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        target.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(target);

        MethodNode handle = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handle", "()Ljava/lang/invoke/MethodHandle;", null, null);
        handle.instructions.add(new LdcInsnNode(new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                node.name, "target", "(I)I", false)));
        handle.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(handle);

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "var-handles", false));
        new CondyIndirectionTransformer().transform(pool, new MappingCollector(), config);

        for (MethodNode method : List.of(number, type, methodType, handle)) {
            assertTrue(method.instructions.getFirst() instanceof LdcInsnNode ldc
                    && ldc.cst instanceof ConstantDynamic);
        }
        ConstantDynamic handleCondy = (ConstantDynamic) ((LdcInsnNode) handle.instructions.getFirst()).cst;
        assertFalse(bootstrapArguments(handleCondy).stream()
                .anyMatch(argument -> argument instanceof org.objectweb.asm.Handle));
        assertFalse(bootstrapArguments(handleCondy).stream()
                .anyMatch(argument -> String.valueOf(argument).contains("target")));

        Class<?> generated = load(pool, node.name);
        assertEquals(42, generated.getMethod("number").invoke(null));
        assertEquals(String.class, generated.getMethod("type").invoke(null));
        assertEquals(java.lang.invoke.MethodType.methodType(String.class, int.class),
                generated.getMethod("methodType").invoke(null));
        java.lang.invoke.MethodHandle resolved = (java.lang.invoke.MethodHandle) generated.getMethod("handle").invoke(null);
        assertEquals(19, assertDoesNotThrow(() -> (int) resolved.invokeExact(19)));
    }

    @Test
    void largeAndNonStandardUtf16StringsRoundTripAcrossCiphertextChunks() throws Exception {
        String expected = "A\0\ud800Z" + "frost".repeat(12_000);
        ClassNode node = stringClass("com/example/LargeCondy", expected);
        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "method-handles", false, "var-handles", false));

        new CondyIndirectionTransformer().transform(pool, new MappingCollector(), config);

        ConstantDynamic dynamic = (ConstantDynamic) ((LdcInsnNode) node.methods.get(1)
                .instructions.getFirst()).cst;
        long ciphertextChunks = bootstrapArguments(dynamic).stream()
                .filter(String.class::isInstance).map(String.class::cast)
                .filter(value -> !value.isEmpty()).count();
        assertTrue(ciphertextChunks >= 2, "Large constants should be split into class-file-safe chunks");
        Class<?> generated = load(pool, node.name);
        assertEquals(expected, generated.getMethod("value").invoke(null));
    }

    @Test
    void corruptedCiphertextFailsAuthenticatedBootstrapResolution() throws Exception {
        ClassNode node = stringClass("com/example/TamperedCondy", "integrity-bound-secret");
        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "method-handles", false, "var-handles", false));
        new CondyIndirectionTransformer().transform(pool, new MappingCollector(), config);

        LdcInsnNode ldc = (LdcInsnNode) node.methods.get(1).instructions.getFirst();
        ConstantDynamic original = (ConstantDynamic) ldc.cst;
        Object[] arguments = bootstrapArguments(original).toArray();
        String ciphertext = (String) arguments[0];
        arguments[0] = (ciphertext.charAt(0) == 'A' ? "B" : "A") + ciphertext.substring(1);
        ldc.cst = new ConstantDynamic(original.getName(), original.getDescriptor(),
                original.getBootstrapMethod(), arguments);

        Class<?> generated = load(pool, node.name);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> generated.getMethod("value").invoke(null));
        assertInstanceOf(BootstrapMethodError.class, failure.getCause());
    }

    @Test
    void invokedynamicBootstrapArgumentsAreEncryptedAndRemainExecutable() throws Exception {
        ClassNode node = new ClassNode();
        node.name = "com/example/BootstrapArguments";
        node.superName = "java/lang/Object";
        node.access = Opcodes.ACC_PUBLIC;
        node.version = Opcodes.V11;
        MethodNode concat = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "concat", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        concat.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        InvokeDynamicInsnNode dynamic = new InvokeDynamicInsnNode("makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                "prefix=\u0001");
        concat.instructions.add(dynamic);
        concat.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(concat);

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "method-handles", false, "var-handles", false));
        new CondyIndirectionTransformer().transform(pool, new MappingCollector(), config);

        assertInstanceOf(ConstantDynamic.class, dynamic.bsmArgs[0]);
        assertFalse(bootstrapArguments((ConstantDynamic) dynamic.bsmArgs[0]).contains("prefix=\u0001"));
        Class<?> generated = load(pool, node.name);
        assertEquals("prefix=value", generated.getMethod("concat", String.class).invoke(null, "value"));
    }

    private static List<Object> bootstrapArguments(ConstantDynamic dynamic) {
        List<Object> arguments = new ArrayList<>();
        for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
            arguments.add(dynamic.getBootstrapMethodArgument(index));
        }
        return arguments;
    }

    private static ClassNode stringClass(String name, String value) {
        ClassNode node = new ClassNode();
        node.name = name;
        node.superName = "java/lang/Object";
        node.access = Opcodes.ACC_PUBLIC;
        node.version = Opcodes.V11;
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode(value));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
        return node;
    }

    private static Class<?> load(ClassPool pool, String mainClass) throws Exception {
        Map<String, byte[]> bytecode = new HashMap<>();
        for (ClassNode node : pool.getClasses()) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            bytecode.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        ClassLoader loader = new ClassLoader(CondyIndirectionTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] data = bytecode.get(name);
                if (data == null) return super.findClass(name);
                return defineClass(name, data, 0, data.length);
            }
        };
        return loader.loadClass(mainClass.replace('/', '.'));
    }
}
