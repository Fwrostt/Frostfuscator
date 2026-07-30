package dev.frost.obfuscator.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NestedFatJarTest {

    @TempDir
    Path tempDir;

    @Test
    void testNestedFatJarExtractionAndRepacking() throws Exception {
        // Create an inner nested JAR
        ByteArrayOutputStream innerJarBaos = new ByteArrayOutputStream();
        try (JarOutputStream innerJos = new JarOutputStream(innerJarBaos)) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/nested/NestedClass", null, "java/lang/Object", null);
            cw.visitEnd();
            innerJos.putNextEntry(new JarEntry("com/example/nested/NestedClass.class"));
            innerJos.write(cw.toByteArray());
            innerJos.closeEntry();
        }
        byte[] innerJarBytes = innerJarBaos.toByteArray();

        // Create outer Spring Boot style Fat JAR
        Path outerJarPath = tempDir.resolve("outer-app.jar");
        try (JarOutputStream outerJos = new JarOutputStream(Files.newOutputStream(outerJarPath))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/main/MainApp", null, "java/lang/Object", null);
            cw.visitEnd();
            outerJos.putNextEntry(new JarEntry("com/example/main/MainApp.class"));
            outerJos.write(cw.toByteArray());
            outerJos.closeEntry();

            outerJos.putNextEntry(new JarEntry("BOOT-INF/lib/nested-lib.jar"));
            outerJos.write(innerJarBytes);
            outerJos.closeEntry();
        }

        JarProcessor processor = new JarProcessor();
        ClassPool pool = processor.loadJar(outerJarPath);

        // Verify class from nested JAR was loaded into pool
        assertNotNull(pool.getClass("com/example/nested/NestedClass"));
        assertNotNull(pool.getClass("com/example/main/MainApp"));

        // Moving succeeds on Windows only when both the outer JarFile and nested entry stream closed.
        Path movedJar = tempDir.resolve("outer-app-moved.jar");
        Files.move(outerJarPath, movedJar);
        Files.move(movedJar, outerJarPath);

        Path outputJarPath = tempDir.resolve("outer-app-protected.jar");
        processor.writeJar(pool, outputJarPath);

        assertTrue(Files.exists(outputJarPath));

        // Verify nested JAR is repacked inside output JAR
        try (JarFile jarFile = new JarFile(outputJarPath.toFile())) {
            JarEntry nestedEntry = jarFile.getJarEntry("BOOT-INF/lib/nested-lib.jar");
            assertNotNull(nestedEntry);

            try (JarInputStream jis = new JarInputStream(jarFile.getInputStream(nestedEntry))) {
                JarEntry innerClassEntry = jis.getNextJarEntry();
                assertNotNull(innerClassEntry);
                assertEquals("com/example/nested/NestedClass.class", innerClassEntry.getName());
            }
        }
    }
}
