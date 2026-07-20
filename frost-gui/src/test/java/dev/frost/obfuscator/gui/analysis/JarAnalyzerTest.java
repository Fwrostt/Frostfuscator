package dev.frost.obfuscator.gui.analysis;

import dev.frost.obfuscator.gui.config.ConfigurationBinder;
import dev.frost.obfuscator.gui.state.ProjectState;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class JarAnalyzerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsManifestRuntimeAndSensitiveArchiveFeatures() throws Exception {
        Path jar = temporaryDirectory.resolve("sample.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.Main");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/dependency.jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream archive = new JarOutputStream(output, manifest)) {
            add(archive, "com/example/Main.class", fakeClass(65, "java/lang/reflect Class forName"));
            add(archive, "META-INF/services/com.example.Plugin", "com.example.PluginImpl".getBytes(StandardCharsets.UTF_8));
            add(archive, "native/example.dll", new byte[]{1, 2, 3});
            add(archive, "META-INF/SAMPLE.SF", new byte[]{1});
            add(archive, "BOOT-INF/lib/nested.jar", new byte[]{4, 5, 6});
        }

        ProjectAnalysis analysis = new JarAnalyzer().analyze(jar);

        assertEquals(1, analysis.classCount());
        assertEquals(21, analysis.javaVersion());
        assertEquals("com.example.Main", analysis.mainClass());
        assertTrue(analysis.reflectionUsage());
        assertTrue(analysis.serviceLoaders());
        assertTrue(analysis.nativeLibraries());
        assertTrue(analysis.signed());
        assertTrue(analysis.fatJar());
        assertTrue(analysis.inventory().resourceEntries().stream()
                .anyMatch(resource -> resource.name().equals("native/example.dll")));
        assertTrue(analysis.suggestedOutput().endsWith("sample-protected.jar"));
        assertFalse(analysis.keepRules().isEmpty());
    }

    @Test
    void inventoriesEveryClassMethodFieldInstructionAndStringAndAppliesKeepRules() throws Exception {
        Path jar = temporaryDirectory.resolve("inventory.jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream archive = new JarOutputStream(output)) {
            add(archive, "com/example/Inventory.class", inventoryClass());
        }

        ProjectAnalysis analysis = new JarAnalyzer().analyze(jar);
        BytecodeInventory inventory = analysis.inventory();

        assertEquals(1, analysis.classCount());
        assertEquals(25, analysis.javaVersion());
        assertEquals(1, inventory.fieldCount());
        assertTrue(inventory.methodCount() >= 2);
        assertTrue(inventory.instructionCount() > 0);
        assertEquals(2, inventory.stringLiteralCount());
        assertEquals(2, inventory.uniqueStringCount());
        assertFalse(inventory.classes().isEmpty());
        assertFalse(inventory.methods().isEmpty());
        assertTrue(inventory.strings().stream().anyMatch(item -> item.value().equals("visible-secret")));
        assertTrue(analysis.reflectionUsage());
        assertTrue(inventory.compatibilitySignals().stream()
                .anyMatch(signal -> signal.id().equals("reflection")));

        ProjectState state = new ProjectState();
        new ConfigurationBinder(state);
        state.setAnalysis(analysis);
        state.configuration().getTransformerConfig("class-rename").setEnabled(true);
        Recommendation keep = new RecommendationEngine().recommend(analysis, state.configuration(),
                        "Development", 0, 0.5).stream()
                .filter(item -> item.id().equals("reflection-keep"))
                .findFirst().orElseThrow();
        assertTrue(keep.actionable());
        keep.action().accept(state);
        assertTrue(state.configuration().getExclusions().containsAll(analysis.exclusions()));
        assertTrue(new RecommendationEngine().recommend(analysis, state.configuration(),
                        "Development", 0, 0.5).stream()
                .noneMatch(item -> item.id().equals("reflection-keep")));
    }

    private static void add(JarOutputStream archive, String name, byte[] value) throws Exception {
        archive.putNextEntry(new JarEntry(name));
        archive.write(value);
        archive.closeEntry();
    }

    private static byte[] fakeClass(int major, String constants) {
        byte[] suffix = constants.getBytes(StandardCharsets.ISO_8859_1);
        byte[] bytes = new byte[8 + suffix.length];
        bytes[0] = (byte) 0xCA;
        bytes[1] = (byte) 0xFE;
        bytes[2] = (byte) 0xBA;
        bytes[3] = (byte) 0xBE;
        bytes[6] = (byte) (major >>> 8);
        bytes[7] = (byte) major;
        System.arraycopy(suffix, 0, bytes, 8, suffix.length);
        return bytes;
    }

    private static byte[] inventoryClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(69, Opcodes.ACC_PUBLIC, "com/example/Inventory", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "value",
                "Ljava/lang/String;", null, "visible-secret").visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor lookup = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lookup", "()Ljava/lang/Class;", null, new String[]{"java/lang/ClassNotFoundException"});
        lookup.visitCode();
        lookup.visitLdcInsn("com.example.Target");
        lookup.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        lookup.visitInsn(Opcodes.ARETURN);
        lookup.visitMaxs(0, 0);
        lookup.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
