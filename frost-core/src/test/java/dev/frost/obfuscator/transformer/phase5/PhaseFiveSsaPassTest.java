package dev.frost.obfuscator.transformer.phase5;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.engine.ProtectionStats;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.transformer.virtualization.VirtualizationTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseFiveSsaPassTest {
    @Test
    void outlinesPureDefUseSliceAndExecutesGeneratedHelper() throws Exception {
        String owner = "fixture/PhaseFiveOutline";
        ClassNode node = owner(owner);
        MethodNode source = arithmetic("compute");
        node.methods.add(source);

        SsaExpressionOutliningPass pass = new SsaExpressionOutliningPass(owner, "$outlined", 5, 64, 16);
        IrMethodPassAdapter.Result result = new IrMethodPassAdapter().run(owner, source, pass, 41L);

        assertTrue(result.changed(), result.message());
        node.methods.set(0, result.output().orElseThrow());
        node.methods.add(pass.buildHelper());
        Class<?> loaded = load(Map.of(owner, node), owner);
        Method compute = loaded.getMethod("compute", int.class, int.class);
        for (int left : new int[]{Integer.MIN_VALUE, -7, 0, 13, Integer.MAX_VALUE}) {
            for (int right : new int[]{-3, 0, 11}) {
                assertEquals((left + right) * (left - right), compute.invoke(null, left, right));
            }
        }
    }

    @Test
    void interleavesSsaBranchesAndExecutesJoinedWorkers() throws Exception {
        String owner = "fixture/PhaseFiveThreads";
        ClassNode node = owner(owner);
        MethodNode source = arithmetic("compute");
        node.methods.add(source);
        SsaThreadInterleavingPass pass = new SsaThreadInterleavingPass(Opcodes.V17,
                "fixture/PhaseFiveLeftWorker", "fixture/PhaseFiveRightWorker",
                100, 3, 7, 96, 16);

        IrMethodPassAdapter.Result result = new IrMethodPassAdapter().run(owner, source, pass, 73L);

        assertTrue(result.changed(), result.message());
        assertEquals(2, pass.workers().size());
        node.methods.set(0, result.output().orElseThrow());
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        classes.put(owner, node);
        pass.workers().forEach(worker -> classes.put(worker.name, worker));
        Class<?> loaded = load(classes, owner);
        Method compute = loaded.getMethod("compute", int.class, int.class);
        assertEquals((19 + -4) * (19 - -4), compute.invoke(null, 19, -4));
    }

    @Test
    void lowersRegisterCopiesWithoutChangingArithmetic() throws Exception {
        String owner = "fixture/PhaseFiveCopies";
        ClassNode node = owner(owner);
        MethodNode source = arithmetic("compute");
        node.methods.add(source);
        SsaCopyWeavingPass pass = new SsaCopyWeavingPass("test.phase5.copies", 100, 8);

        IrMethodPassAdapter.Result result = new IrMethodPassAdapter().run(owner, source, pass, 91L);

        assertTrue(result.changed(), result.message());
        assertTrue(result.metric("copies") > 0);
        node.methods.set(0, result.output().orElseThrow());
        Class<?> loaded = load(Map.of(owner, node), owner);
        assertEquals((8 + 3) * (8 - 3), loaded.getMethod("compute", int.class, int.class)
                .invoke(null, 8, 3));
    }

    @Test
    void weavesOnlyTrackedDecryptKeyThroughRealMethodData() throws Exception {
        String owner = "fixture/PhaseFiveStrings";
        ClassNode node = owner(owner);
        MethodNode decrypt = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "$decrypt", "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
        decrypt.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        decrypt.instructions.add(new InsnNode(Opcodes.ARETURN));
        decrypt.maxLocals = 2;
        decrypt.maxStack = 1;
        node.methods.add(decrypt);

        MethodNode source = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "decode", "(I)Ljava/lang/String;", null, null);
        source.instructions.add(new LdcInsnNode("ciphertext"));
        source.instructions.add(new LdcInsnNode(0x13579bdf));
        source.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "$decrypt",
                "(Ljava/lang/String;I)Ljava/lang/String;", false));
        source.instructions.add(new InsnNode(Opcodes.ARETURN));
        source.maxLocals = 1;
        source.maxStack = 2;
        node.methods.add(source);

        StringKeyDataFlowPass pass = new StringKeyDataFlowPass(owner, Set.of("$decrypt"), 4);
        IrMethodPassAdapter.Result result = new IrMethodPassAdapter().run(owner, source, pass, 117L);

        assertTrue(result.changed(), result.message());
        assertEquals(1, result.metric("wovenKeys"));
        assertTrue(hasOpcode(result.output().orElseThrow(), Opcodes.IADD));
        assertTrue(hasOpcode(result.output().orElseThrow(), Opcodes.ISUB));
        node.methods.set(1, result.output().orElseThrow());
        Class<?> loaded = load(Map.of(owner, node), owner);
        assertEquals("ciphertext", loaded.getMethod("decode", int.class).invoke(null, 29));
    }

    @Test
    void virtualizationConsumesSsaPreparedRegisterLayoutAndExecutes() throws Exception {
        String owner = "fixture/PhaseFiveVirtualized";
        ClassNode node = owner(owner);
        node.methods.add(arithmetic("compute"));
        ClassPool pool = new ClassPool();
        pool.addClass(owner, node);

        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("probability", 100),
                Map.entry("min-method-instructions", 4),
                Map.entry("max-method-instructions", 300),
                Map.entry("skip-initializers", true),
                Map.entry("encrypt-bytecode", true),
                Map.entry("max-locals", 256),
                Map.entry("max-stack", 512),
                Map.entry("seed", 123),
                Map.entry("ssa-register-weave-probability", 100),
                Map.entry("ssa-max-register-copies", 6)
        )));
        ProtectionStats stats = new ProtectionStats();
        Context context = new Context(pool, null, new MappingCollector(), config, stats, null, null);

        new VirtualizationTransformer().transform(context);

        assertEquals(1, stats.get("virtualizedMethods"));
        assertEquals(1, stats.get("virtualizationSsaEncodedMethods"));
        assertEquals(0, stats.get("virtualizationAsmFallbackMethods"));
        Class<?> loaded = load(pool.getClassMap(), owner);
        Method compute = loaded.getMethod("compute", int.class, int.class);
        for (int left : new int[]{Integer.MIN_VALUE, -9, 0, 17, Integer.MAX_VALUE}) {
            for (int right : new int[]{-5, 0, 23}) {
                assertEquals((left + right) * (left - right), compute.invoke(null, left, right));
            }
        }
    }

    private MethodNode arithmetic(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, "(II)I", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ISUB));
        method.instructions.add(new InsnNode(Opcodes.IMUL));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 3;
        return method;
    }

    private ClassNode owner(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private boolean hasOpcode(MethodNode method, int opcode) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) return true;
        }
        return false;
    }

    private Class<?> load(Map<String, ClassNode> nodes, String mainOwner) throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (ClassNode node : nodes.values()) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            classes.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    byte[] bytes = classes.get(name);
                    if (loaded == null && bytes != null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (loaded == null) {
                        loaded = super.loadClass(name, false);
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        return loader.loadClass(mainOwner.replace('/', '.'));
    }
}
