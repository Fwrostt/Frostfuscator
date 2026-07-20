package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.config.ObfuscationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ClassVersionCompatibilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void processesJava25BytecodeWithoutChangingItsClassVersion() throws Exception {
        Path input = temporaryDirectory.resolve("java25-input.jar");
        Path output = temporaryDirectory.resolve("java25-output.jar");
        writeJar(input, classBytes(69));

        ObfuscationConfig config = new ObfuscationConfig();
        config.setInput(input.toString());
        config.setOutput(output.toString());
        config.getLibraries().setRuntime(false);
        new ObfuscationEngine(config, null).run();

        assertTrue(Files.isRegularFile(output));
        try (JarFile jar = new JarFile(output.toFile())) {
            byte[] written = jar.getInputStream(jar.getJarEntry("sample/Java25.class")).readAllBytes();
            assertEquals(69, ((written[6] & 0xff) << 8) | (written[7] & 0xff));
        }
    }

    @Test
    void reportsTheEntryAndJavaReleaseForGenuinelyNewerBytecode() throws Exception {
        Path input = temporaryDirectory.resolve("future-input.jar");
        writeJar(input, classBytes(71));

        IOException failure = assertThrows(IOException.class,
                () -> new JarProcessor().loadJar(input));

        assertTrue(failure.getMessage().contains("sample/Java25.class"));
        assertTrue(failure.getMessage().contains("major version 71"));
        assertTrue(failure.getMessage().contains("Java 27"));
        assertTrue(failure.getMessage().contains("through Java 26"));
    }

    private static void writeJar(Path path, byte[] classBytes) throws Exception {
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("sample/Java25.class"));
            jar.write(classBytes);
            jar.closeEntry();
        }
    }

    private static byte[] classBytes(int major) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(69, Opcodes.ACC_PUBLIC, "sample/Java25", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        bytes[6] = (byte) (major >>> 8);
        bytes[7] = (byte) major;
        return bytes;
    }
}
