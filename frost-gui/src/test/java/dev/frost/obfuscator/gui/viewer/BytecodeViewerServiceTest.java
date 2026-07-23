package dev.frost.obfuscator.gui.viewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeViewerServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void vineflowerDecompilesASelectedClass() throws Exception {
        Path archive = fixture();

        DecompileResult result = new VineflowerBackend()
                .decompile(archive, "example/Sample.class");

        assertTrue(result.source().contains("class Sample"), result.source());
        assertTrue(result.source().contains("visible-value"), result.source());
        assertTrue(result.source().contains("static void main"), result.source());
    }

    @Test
    void cfrDecompilesASelectedClass() throws Exception {
        Path archive = fixture();

        DecompileResult result = new CfrBackend()
                .decompile(archive, "example/Sample.class");

        assertTrue(result.source().contains("class Sample"), result.source());
        assertTrue(result.source().contains("visible-value"), result.source());
        assertTrue(result.source().contains("main"), result.source());
    }

    @Test
    void procyonDecompilesASelectedClass() throws Exception {
        Path archive = fixture();

        DecompileResult result = new ProcyonBackend()
                .decompile(archive, "example/Sample.class");

        assertTrue(result.source().contains("class Sample"), result.source());
        assertTrue(result.source().contains("visible-value"), result.source());
        assertTrue(result.source().contains("main"), result.source());
    }

    @Test
    void fernflowerDecompilesASelectedClass() throws Exception {
        Path archive = fixture();

        DecompileResult result = new FernflowerBackend()
                .decompile(archive, "example/Sample.class");

        assertTrue(result.source().contains("class Sample"), result.source());
        assertTrue(result.source().contains("visible-value"), result.source());
        assertTrue(result.source().contains("main"), result.source());
    }

    @Test
    void inspectorBuildsHierarchyMetadataStringsCallsAndCapabilityEvidence() throws Exception {
        Path archive = fixture();
        ArchiveInspector inspector = new ArchiveInspector();

        ArchiveInspector.ArchiveSnapshot snapshot = inspector.open(archive);
        ArchiveInspector.ClassInspection sample =
                inspector.inspectClass(archive, "example/Sample.class");
        ArchiveInspector.ArchiveScan scan = inspector.scan(archive);

        assertEquals(1, snapshot.classCount());
        assertEquals(1, snapshot.resourceCount());
        assertEquals("example.Sample", sample.className());
        assertEquals(65, sample.classMajor());
        assertTrue(sample.methods().stream().anyMatch(ArchiveInspector.MemberInfo::mainMethod));
        assertTrue(sample.strings().stream().anyMatch(item -> item.value().equals("visible-value")));
        assertTrue(sample.calls().stream().anyMatch(item -> item.target().contains("java.lang.Runtime.exec")));
        assertTrue(scan.findings().stream()
                .anyMatch(item -> item.category().equals("Process execution") && item.severity().equals("High")));
        assertTrue(inspector.resourcePreview(archive, "assets/readme.txt").contains("viewer fixture"));
    }

    @Test
    void exportToolsWriteCopiesAndNeverMutateTheInput() throws Exception {
        Path archive = fixture();
        byte[] original = Files.readAllBytes(archive);
        ArchiveRewriteService rewrites = new ArchiveRewriteService();
        ArchiveInspector inspector = new ArchiveInspector();

        Path replaced = temporaryDirectory.resolve("replaced.jar");
        ArchiveRewriteService.RewriteSummary replacement =
                rewrites.replaceStrings(archive, replaced, "visible-value", "changed-value");
        assertEquals(2, replacement.changedValues());
        assertTrue(inspector.inspectClass(replaced, "example/Sample.class").strings().stream()
                .anyMatch(item -> item.value().equals("changed-value")));

        Path versioned = temporaryDirectory.resolve("versioned.jar");
        rewrites.changeClassVersion(archive, versioned, 69);
        assertEquals(69, inspector.inspectClass(versioned, "example/Sample.class").classMajor());

        Path frameless = temporaryDirectory.resolve("frameless.jar");
        rewrites.removeStackFrames(archive, frameless);
        assertEquals(0, frameCount(classBytes(frameless)));
        assertArrayEquals(original, Files.readAllBytes(archive), "Viewer tools must not alter the selected input");
    }

    private Path fixture() throws Exception {
        Path archive = temporaryDirectory.resolve("viewer-fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("example/Sample.class"));
            output.write(sampleClass());
            output.closeEntry();
            output.putNextEntry(new JarEntry("assets/readme.txt"));
            output.write("viewer fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return archive;
    }

    private static byte[] sampleClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/Sample", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "VALUE", "Ljava/lang/String;", null, "visible-value").visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "main", "([Ljava/lang/String;)V", null, new String[]{"java/io/IOException"});
        main.visitCode();
        main.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime",
                "()Ljava/lang/Runtime;", false);
        main.visitLdcInsn("visible-value");
        main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exec",
                "(Ljava/lang/String;)Ljava/lang/Process;", false);
        main.visitInsn(Opcodes.POP);
        main.visitInsn(Opcodes.RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();

        MethodVisitor branch = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "branch", "(Z)I", null, null);
        branch.visitCode();
        Label falseLabel = new Label();
        branch.visitVarInsn(Opcodes.ILOAD, 0);
        branch.visitJumpInsn(Opcodes.IFEQ, falseLabel);
        branch.visitInsn(Opcodes.ICONST_1);
        branch.visitInsn(Opcodes.IRETURN);
        branch.visitLabel(falseLabel);
        branch.visitInsn(Opcodes.ICONST_0);
        branch.visitInsn(Opcodes.IRETURN);
        branch.visitMaxs(0, 0);
        branch.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classBytes(Path archive) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            try (var stream = jar.getInputStream(jar.getJarEntry("example/Sample.class"))) {
                return stream.readAllBytes();
            }
        }
    }

    private static long frameCount(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().flatMap(method -> {
            java.util.List<AbstractInsnNode> values = new java.util.ArrayList<>();
            if (method.instructions != null) {
                for (AbstractInsnNode item = method.instructions.getFirst();
                     item != null; item = item.getNext()) values.add(item);
            }
            return values.stream();
        }).filter(FrameNode.class::isInstance).count();
    }
}
