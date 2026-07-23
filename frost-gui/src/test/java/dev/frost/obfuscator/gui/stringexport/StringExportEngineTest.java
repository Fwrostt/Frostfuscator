package dev.frost.obfuscator.gui.stringexport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class StringExportEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsStringsAndExportsAllFormats() throws Exception {
        Path archive = createFixture();
        StringExportEngine engine = new StringExportEngine();

        List<StringRecord> records = engine.extractAllStrings(archive, true);
        assertFalse(records.isEmpty());

        assertTrue(records.stream().anyMatch(r -> r.value().equals("https://frost.example/protected")));
        assertTrue(records.stream().anyMatch(r -> r.value().equals("SELECT * FROM secret_table")));

        Path txt = tempDir.resolve("reports/strings.txt");
        Path csv = tempDir.resolve("reports/strings.csv");
        Path json = tempDir.resolve("reports/strings.json");
        Path jsonl = tempDir.resolve("reports/strings.jsonl");
        Files.createDirectories(txt.getParent());

        engine.exportTxt(records, txt);
        engine.exportCsv(records, csv);
        engine.exportJson(records, json);
        engine.exportJsonl(records, jsonl);

        assertTrue(Files.exists(txt));
        assertTrue(Files.exists(csv));
        assertTrue(Files.exists(json));
        assertTrue(Files.exists(jsonl));

        String jsonContent = Files.readString(json);
        assertTrue(jsonContent.contains("https://frost.example/protected"));
    }

    private Path createFixture() throws Exception {
        Path archive = tempDir.resolve("string-fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\r\nMain-Class: com.acme.Sample\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("com/acme/Sample.class"));
            output.write(sampleClass());
            output.closeEntry();
        }
        return archive;
    }

    private static byte[] sampleClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/Sample", null, "java/lang/Object", null);

        FieldVisitor field = writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "API_URL", "Ljava/lang/String;", null, "https://frost.example/protected");
        field.visitEnd();

        MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitLdcInsn("SELECT * FROM secret_table");
        main.visitInsn(Opcodes.POP);
        main.visitInsn(Opcodes.RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
