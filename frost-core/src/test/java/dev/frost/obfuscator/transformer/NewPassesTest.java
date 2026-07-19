package dev.frost.obfuscator.transformer;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.engine.JarProcessor;
import dev.frost.obfuscator.engine.ProtectionStats;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.funsies.DecompilerZipTiesTransformer;
import dev.frost.obfuscator.transformer.funsies.TrollStackTracesTransformer;
import dev.frost.obfuscator.transformer.optimization.AggressiveInliningTransformer;
import dev.frost.obfuscator.transformer.optimization.DeadCodeEliminationTransformer;
import dev.frost.obfuscator.transformer.protection.AntiAttachTransformer;
import dev.frost.obfuscator.transformer.protection.ArchiveExtractionCanaryTransformer;
import dev.frost.obfuscator.transformer.protection.RuntimeSelfChecksumTransformer;
import dev.frost.obfuscator.transformer.protection.StructuralHardeningTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceSplittingTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceSteganographyTransformer;
import dev.frost.runtime.SplitResourceLoader;
import dev.frost.runtime.StegoResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class NewPassesTest {
    @TempDir
    Path tempDir;

    @Test
    void inlinesTinyPrivateStaticMethod() {
        ClassNode node = classNode("example/Inline");
        MethodNode helper = method(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "answer", "()I",
                new InsnNode(Opcodes.ICONST_5), new InsnNode(Opcodes.IRETURN));
        MethodNode caller = method(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()I",
                new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "answer", "()I", false),
                new InsnNode(Opcodes.IRETURN));
        node.methods.add(helper);
        node.methods.add(caller);
        Context context = context(node, new TransformerConfig());

        new AggressiveInliningTransformer().transform(context);

        assertFalse(node.methods.contains(helper));
        assertEquals(Opcodes.ICONST_5, caller.instructions.getFirst().getOpcode());
    }

    @Test
    void removesOnlyUnreachablePrivateMembers() {
        ClassNode node = classNode("example/Dead");
        MethodNode live = method(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "live", "()V",
                new InsnNode(Opcodes.RETURN));
        MethodNode dead = method(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "dead", "()V",
                new InsnNode(Opcodes.RETURN));
        MethodNode api = method(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "api", "()V",
                new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "live", "()V", false),
                new InsnNode(Opcodes.RETURN));
        node.methods.add(live);
        node.methods.add(dead);
        node.methods.add(api);

        new DeadCodeEliminationTransformer().transform(context(node, new TransformerConfig()));

        assertTrue(node.methods.contains(live));
        assertFalse(node.methods.contains(dead));
        assertTrue(node.methods.contains(api));
    }

    @Test
    void rewritesExplicitStackTracePrinting() {
        ClassNode node = classNode("example/Trace");
        MethodNode method = method(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "print", "(Ljava/lang/Throwable;)V",
                new VarInsnNode(Opcodes.ALOAD, 0),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false),
                new InsnNode(Opcodes.RETURN));
        node.methods.add(method);

        new TrollStackTracesTransformer().transform(context(node, new TransformerConfig()));

        MethodInsnNode call = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode found) call = found;
        }
        assertNotNull(call);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals("__frost$troll$trace", call.name);
    }

    @Test
    void zipTiesAreBoundedAndVerifierSafeMetadata() {
        ClassNode node = classNode("example/Tied");
        TransformerConfig config = new TransformerConfig();
        config.getOptions().put("generic-depth", 16);

        new DecompilerZipTiesTransformer().transform(context(node, config));

        assertEquals(2, node.fields.size());
        assertTrue(node.fields.getFirst().signature.startsWith("Ljava/util/List<"));
        assertEquals("Ljava/util/List;", node.fields.getFirst().desc);
    }

    @Test
    void steganographicResourceRoundTripsThroughRuntimeReader() throws Exception {
        byte[] original = "very secret configuration".getBytes(StandardCharsets.UTF_8);
        JarProcessor jar = new JarProcessor();
        jar.putResource("config.yml", original);
        TransformerConfig config = new TransformerConfig();
        config.getOptions().put("password", "test-password");
        Context context = context(new ClassPool(), jar, config);

        new ResourceSteganographyTransformer().transform(context);

        assertFalse(jar.getResources().containsKey("config.yml"));
        assertTrue(context.pool().contains("dev/frost/runtime/StegoResourceLoader"));
        withResources(jar.getResources(), () ->
                assertArrayEquals(original, StegoResourceLoader.read("config.yml", "test-password")));
    }

    @Test
    void splitResourceRoundTripsThroughRuntimeReader() throws Exception {
        byte[] original = "0123456789abcdef".repeat(64).getBytes(StandardCharsets.UTF_8);
        JarProcessor jar = new JarProcessor();
        jar.putResource("database.bin", original);
        TransformerConfig config = new TransformerConfig();
        config.getOptions().put("minimum-size", 1);
        config.getOptions().put("part-size", 256);
        Context context = context(new ClassPool(), jar, config);

        new ResourceSplittingTransformer().transform(context);

        assertFalse(jar.getResources().containsKey("database.bin"));
        assertTrue(context.pool().contains("dev/frost/runtime/SplitResourceLoader"));
        withResources(jar.getResources(), () ->
                assertArrayEquals(original, SplitResourceLoader.read("database.bin")));
    }

    @Test
    void antiAttachInjectsRuntimeAndClassInitializerGuard() {
        ClassNode node = classNode("example/AttachGuarded");
        TransformerConfig config = new TransformerConfig();
        config.getOptions().put("require-disable-attach", false);
        config.getOptions().put("reject-attach-listener", true);
        Context context = context(node, config);

        new AntiAttachTransformer().transform(context);

        assertFalse(context.pool().contains("dev/frost/runtime/AntiAttachRuntime"));
        MethodNode clinit = node.methods.stream()
                .filter(method -> "<clinit>".equals(method.name))
                .findFirst()
                .orElseThrow();
        assertTrue(containsCallOwnerDifferentFrom(clinit, "dev/frost/runtime/AntiAttachRuntime"));
    }

    @Test
    void runtimeSelfChecksumWritesIndexFromFinalJarBytes() throws Exception {
        ClassNode node = classNode("example/Checksum");
        TransformerConfig config = new TransformerConfig();
        Context context = context(node, config);

        new RuntimeSelfChecksumTransformer().transform(context);

        Path output = tempDir.resolve("checksum.jar");
        context.jar().writeJar(context.pool(), output);

        try (JarFile jar = new JarFile(output.toFile())) {
            assertNotNull(jar.getEntry("META-INF/frostfuscator/runtime-checksums.tsv"));
            String index = new String(jar.getInputStream(jar.getEntry("META-INF/frostfuscator/runtime-checksums.tsv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(index.contains("example/Checksum\t"));
            assertNull(jar.getEntry("dev/frost/runtime/SelfChecksumRuntime.class"));
        }
    }

    @Test
    void structuralHardeningAddsVerifierSafeOpaqueAttributes() {
        ClassNode node = classNode("example/Structured");
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "value", "I", null, null));
        node.methods.add(method(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", new InsnNode(Opcodes.RETURN)));
        TransformerConfig config = new TransformerConfig();
        config.getOptions().put("attributes-per-class", 2);
        config.getOptions().put("payload-bytes", 32);

        new StructuralHardeningTransformer().transform(context(node, config));

        assertEquals(2, node.attrs.size());
        assertFalse(node.methods.getFirst().attrs.isEmpty());
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        assertDoesNotThrow(() -> new ClassReader(writer.toByteArray()));
    }

    @Test
    void archiveExtractionCanaryIsBounded() {
        JarProcessor jar = new JarProcessor();
        TransformerConfig config = new TransformerConfig();
        config.getOptions().put("count", 4);
        config.getOptions().put("expanded-size", 8 * 1024 * 1024);
        Context context = context(new ClassPool(), jar, config);

        new ArchiveExtractionCanaryTransformer().transform(context);

        int canaryBytes = jar.getResources().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("META-INF/frostfuscator/canary/")
                        && entry.getKey().endsWith(".dat"))
                .mapToInt(entry -> entry.getValue().length)
                .sum();
        assertEquals(16 * 1024 * 1024, canaryBytes);
        assertTrue(jar.getResources().containsKey("META-INF/frostfuscator/canary/index.tsv"));
    }

    private Context context(ClassNode node, TransformerConfig config) {
        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        return context(pool, new JarProcessor(), config);
    }

    private Context context(ClassPool pool, JarProcessor jar, TransformerConfig config) {
        return new Context(pool, jar, new MappingCollector(), config, new ProtectionStats(),
                Path.of("input.jar"), Path.of("output.jar"));
    }

    private ClassNode classNode(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private MethodNode method(int access, String name, String desc, AbstractInsnNode... instructions) {
        MethodNode method = new MethodNode(access, name, desc, null, null);
        for (AbstractInsnNode instruction : instructions) method.instructions.add(instruction);
        method.maxStack = 2;
        method.maxLocals = 2;
        return method;
    }

    private boolean containsCall(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCallOwnerDifferentFrom(MethodNode method, String forbiddenOwner) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && !forbiddenOwner.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }

    private void withResources(Map<String, byte[]> resources, ThrowingRunnable action) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(previous) {
            @Override
            public InputStream getResourceAsStream(String name) {
                byte[] data = resources.get(name);
                return data == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(data);
            }
        });
        try {
            action.run();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
