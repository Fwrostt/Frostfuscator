package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.protection.MethodSaltingTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadInterleavedFlowTransformerTest {
    private static final String OWNER = "fixture/ThreadFlowSubject";

    @Test
    void executesIndependentPrimitiveBranchesOnJoinedVolatileWorkers() throws Exception {
        ClassPool pool = subjectPool();
        ClassNode owner = pool.getClass(OWNER);
        MethodNode synchronizedMethod = method(owner, "synchronizedValue");
        MethodNode guardedMethod = method(owner, "guardedValue");
        int synchronizedSize = synchronizedMethod.instructions.size();
        int guardedSize = guardedMethod.instructions.size();

        new ThreadInterleavedFlowTransformer().transform(pool, new MappingCollector(), options(4491L));

        assertEquals(7, pool.size(), "three split sites should create two workers each");
        assertTrue(pool.requiresFrameComputation(OWNER));
        assertEquals(synchronizedSize, synchronizedMethod.instructions.size());
        assertEquals(guardedSize, guardedMethod.instructions.size());

        long runAsyncCalls = methodCalls(owner, "java/util/concurrent/CompletableFuture", "runAsync");
        long joinCalls = methodCalls(owner, "java/util/concurrent/CompletableFuture", "join");
        assertEquals(6, runAsyncCalls);
        assertEquals(6, joinCalls);
        assertLaunchesBothBeforeJoining(method(owner, "computeInt"));

        for (ClassNode generated : pool.getClassMap().values()) {
            if (generated == owner) continue;
            assertTrue(pool.isTransformationExcluded(generated.name));
            assertTrue(generated.interfaces.contains("java/lang/Runnable"));
            assertTrue(generated.fields.stream().allMatch(this::isVolatile));
            assertTrue(generated.methods.stream().anyMatch(method -> method.name.equals("run")));
        }

        Class<?> subject = load(pool);
        int[] integers = {Integer.MIN_VALUE, -17, -1, 0, 1, 29, Integer.MAX_VALUE};
        for (int a : integers) {
            for (int b : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
                int c = a ^ 0x55aa55aa;
                int d = b + 31;
                assertEquals((a + b) * (c - d), invokeInt(subject, "computeInt", a, b, c, d));
                int left = (a + b) ^ 0x13579bdf;
                int right = (c - d) & 0x7fffffff;
                assertEquals(left > right ? 1 : 0, invokeInt(subject, "compareInt", a, b, c, d));
            }
        }

        Method computeLong = subject.getMethod("computeLong",
                long.class, long.class, long.class, long.class);
        long[] longs = {Long.MIN_VALUE, -1L, 0L, 1L, 9_000_000_007L, Long.MAX_VALUE};
        for (long a : longs) {
            long b = Long.rotateLeft(a, 13);
            long c = ~a;
            long d = a ^ 0x5a5a5a5a5a5a5a5aL;
            assertEquals((a + b) * (c ^ d), computeLong.invoke(null, a, b, c, d));
        }
    }

    @Test
    void producesDifferentWorkerLayoutsForDifferentSeeds() {
        ClassPool first = subjectPool();
        ClassPool second = subjectPool();
        ThreadInterleavedFlowTransformer transformer = new ThreadInterleavedFlowTransformer();
        transformer.transform(first, new MappingCollector(), options(11L));
        transformer.transform(second, new MappingCollector(), options(12L));

        assertTrue(!first.getClassMap().keySet().equals(second.getClassMap().keySet()),
                "worker owners should be polymorphic across build seeds");
    }

    @Test
    void recognizesStackNeutralMethodSaltsFromEarlierPipelinePasses() throws Exception {
        ClassPool pool = subjectPool();
        TransformerConfig salting = new TransformerConfig();
        salting.setOptions(new LinkedHashMap<>(Map.of(
                "max-salts", 256,
                "probability", 100,
                "seed", 91L
        )));
        new MethodSaltingTransformer().transform(pool, new MappingCollector(), salting);

        new ThreadInterleavedFlowTransformer().transform(pool, new MappingCollector(), options(92L));

        assertTrue(pool.size() > 1, "thread splitting should survive normal-profile method salts");
        Class<?> subject = load(pool);
        assertEquals((17 + -9) * (41 - 3), invokeInt(subject, "computeInt", 17, -9, 41, 3));
    }

    private TransformerConfig options(long seed) {
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("probability", 100),
                Map.entry("max-per-method", 1),
                Map.entry("max-per-class", 8),
                Map.entry("min-branch-instructions", 3),
                Map.entry("min-expression-instructions", 7),
                Map.entry("max-expression-instructions", 96),
                Map.entry("max-capture-slots", 16),
                Map.entry("max-method-instructions", 2000),
                Map.entry("max-output-method-instructions", 8000),
                Map.entry("seed", seed)
        )));
        return config;
    }

    private ClassPool subjectPool() {
        ClassNode owner = new ClassNode();
        owner.version = Opcodes.V17;
        owner.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        owner.name = OWNER;
        owner.superName = "java/lang/Object";
        owner.methods.add(computeInt());
        owner.methods.add(computeLong());
        owner.methods.add(compareInt());
        owner.methods.add(synchronizedValue());
        owner.methods.add(guardedValue());
        ClassPool pool = new ClassPool();
        pool.addClass(owner.name, owner);
        return pool;
    }

    private MethodNode computeInt() {
        MethodNode method = staticMethod("computeInt", "(IIII)I");
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ISUB));
        method.instructions.add(new InsnNode(Opcodes.IMUL));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    private MethodNode computeLong() {
        MethodNode method = staticMethod("computeLong", "(JJJJ)J");
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.LADD));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 6));
        method.instructions.add(new InsnNode(Opcodes.LXOR));
        method.instructions.add(new InsnNode(Opcodes.LMUL));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        return method;
    }

    private MethodNode compareInt() {
        MethodNode method = staticMethod("compareInt", "(IIII)I");
        LabelNode greater = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(0x13579bdf));
        method.instructions.add(new InsnNode(Opcodes.IXOR));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ISUB));
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(0x7fffffff));
        method.instructions.add(new InsnNode(Opcodes.IAND));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGT, greater));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(greater);
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    private MethodNode synchronizedValue() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                "synchronizedValue", "(IIII)I", null, null);
        appendSimpleExpression(method);
        return method;
    }

    private MethodNode guardedValue() {
        MethodNode method = staticMethod("guardedValue", "(IIII)I");
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        appendSimpleExpression(method);
        method.instructions.insertBefore(method.instructions.getLast(), end);
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));
        return method;
    }

    private void appendSimpleExpression(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ISUB));
        method.instructions.add(new InsnNode(Opcodes.IMUL));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
    }

    private MethodNode staticMethod(String name, String descriptor) {
        return new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
    }

    private MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private long methodCalls(ClassNode owner, String targetOwner, String name) {
        return owner.methods.stream().flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(targetOwner) && call.name.equals(name))
                .count();
    }

    private void assertLaunchesBothBeforeJoining(MethodNode method) {
        int launches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("runAsync")) {
                launches++;
            }
            if (call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("join")) {
                assertEquals(2, launches, "both workers must be scheduled before the first join");
                return;
            }
        }
        throw new AssertionError("missing join");
    }

    private boolean isVolatile(FieldNode field) {
        return (field.access & Opcodes.ACC_VOLATILE) != 0;
    }

    private Class<?> load(ClassPool pool) throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (ClassNode node : pool.getClassMap().values()) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            classes.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) return super.findClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(OWNER.replace('/', '.'));
    }

    private int invokeInt(Class<?> owner, String name, int... values) throws Exception {
        Method method = owner.getMethod(name, int.class, int.class, int.class, int.class);
        return (Integer) method.invoke(null, values[0], values[1], values[2], values[3]);
    }
}
