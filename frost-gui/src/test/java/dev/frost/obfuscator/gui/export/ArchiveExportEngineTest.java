package dev.frost.obfuscator.gui.export;

import dev.frost.obfuscator.gui.viewer.CfrBackend;
import dev.frost.obfuscator.gui.viewer.DecompilerBackend;
import dev.frost.obfuscator.gui.viewer.VineflowerBackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveExportEngineTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsSourcesAcrossMultipleDecompilersAndGeneratesComparisonReport() throws Exception {
        Path archive = createFixture();
        Path outputDir = temporaryDirectory.resolve("export_sources");
        ArchiveExportEngine engine = new ArchiveExportEngine();

        List<DecompilerBackend> backends = List.of(new VineflowerBackend(), new CfrBackend());
        ArchiveExportEngine.ExportSummary summary = engine.exportSources(
                archive, backends, ExportOptions.defaults(), outputDir, null);

        assertTrue(summary.exportedFilesCount() >= 2);
        assertTrue(Files.exists(outputDir.resolve("sources/vineflower/example/Sample.java")));
        assertTrue(Files.exists(outputDir.resolve("sources/cfr/example/Sample.java")));
        assertTrue(Files.exists(outputDir.resolve("reports/decompiler-comparison.md")));

        String report = Files.readString(outputDir.resolve("reports/decompiler-comparison.md"));
        assertTrue(report.contains("Decompiler Comparison Report"));
        assertTrue(report.contains("Vineflower"));
        assertTrue(report.contains("CFR"));
    }

    @Test
    void exportsRawBytecodeDisassemblyASMTextifierAndControlFlowGraph() throws Exception {
        Path archive = createFixture();
        Path outputDir = temporaryDirectory.resolve("export_bytecode");
        ArchiveExportEngine engine = new ArchiveExportEngine();

        ArchiveExportEngine.ExportSummary summary = engine.exportRawBytecode(
                archive, outputDir, null, true, true, true, true);

        assertTrue(summary.exportedFilesCount() >= 4);
        assertTrue(Files.exists(outputDir.resolve("bytecode/example/Sample.class")));
        assertTrue(Files.exists(outputDir.resolve("bytecode/example/Sample.bytecode.asm")));
        assertTrue(Files.exists(outputDir.resolve("bytecode/example/Sample.javap.txt")));
        assertTrue(Files.exists(outputDir.resolve("graphs/example/Sample.cfg.mmd")));
    }

    @Test
    void exportsProjectInventoryJsonAndResources() throws Exception {
        Path archive = createFixture();
        Path outputDir = temporaryDirectory.resolve("export_project");
        ArchiveExportEngine engine = new ArchiveExportEngine();

        ArchiveExportEngine.ExportSummary summary = engine.exportProjectResources(archive, outputDir);
        assertTrue(summary.exportedFilesCount() >= 2);
        assertTrue(Files.exists(outputDir.resolve("resources/assets/readme.txt")));
        assertTrue(Files.exists(outputDir.resolve("reports/inventory.json")));

        String json = Files.readString(outputDir.resolve("reports/inventory.json"));
        assertTrue(json.contains("archiveName"));
        assertTrue(json.contains("classCount"));
    }

    @Test
    void rebuildsSanitizedJarSafely() throws Exception {
        Path archive = createFixture();
        Path sanitizedJar = temporaryDirectory.resolve("sanitized.jar");
        ArchiveExportEngine engine = new ArchiveExportEngine();

        ArchiveExportEngine.ExportSummary summary = engine.rebuildSanitizedJar(archive, sanitizedJar);
        assertTrue(Files.exists(sanitizedJar));
        assertTrue(summary.exportedFilesCount() >= 2);
    }

    private Path createFixture() throws Exception {
        Path archive = temporaryDirectory.resolve("export-fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("example/Sample.class"));
            output.write(sampleClass());
            output.closeEntry();
            output.putNextEntry(new JarEntry("assets/readme.txt"));
            output.write("export fixture".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return archive;
    }

    private static byte[] sampleClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/Sample", null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitInsn(Opcodes.RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
