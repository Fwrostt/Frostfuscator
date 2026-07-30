package dev.frost.obfuscator.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedJarFrameRecomputationTest {

    @TempDir
    Path tempDir;

    @Test
    void recomputesStackMapTableWhenRebuildingNestedClass() throws Exception {
        byte[] nestedClass = branchingClass();
        ByteArrayOutputStream nestedJar = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(nestedJar)) {
            output.putNextEntry(new JarEntry("sample/NestedFlow.class"));
            output.write(nestedClass);
            output.closeEntry();
        }

        Path input = tempDir.resolve("input.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(input))) {
            output.putNextEntry(new JarEntry("BOOT-INF/lib/dependency.jar"));
            output.write(nestedJar.toByteArray());
            output.closeEntry();
        }

        JarProcessor processor = new JarProcessor();
        ClassPool pool = processor.loadJar(input);
        ClassNode nested = pool.getClass("sample/NestedFlow");
        nested.methods.forEach(method -> {
            if (method.instructions != null) {
                java.util.Arrays.stream(method.instructions.toArray())
                        .filter(FrameNode.class::isInstance)
                        .forEach(method.instructions::remove);
            }
        });
        pool.markDirty(nested.name);

        Path output = tempDir.resolve("output.jar");
        processor.writeJar(pool, output);
        byte[] rebuilt = nestedClassFrom(output);

        ClassNode verified = new ClassNode();
        new ClassReader(rebuilt).accept(verified, ClassReader.EXPAND_FRAMES);
        long frames = verified.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(FrameNode.class::isInstance)
                .count();
        assertTrue(frames > 0);

        Class<?> loaded = new ByteClassLoader().define(rebuilt);
        assertEquals(1, loaded.getMethod("choose", boolean.class).invoke(null, true));
        assertEquals(2, loaded.getMethod("choose", boolean.class).invoke(null, false));
    }

    private static byte[] branchingClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/NestedFlow", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "choose", "(Z)I", null, null);
        method.visitCode();
        Label alternate = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, alternate);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(alternate);
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] nestedClassFrom(Path outerJar) throws Exception {
        try (JarFile outer = new JarFile(outerJar.toFile())) {
            byte[] nestedJar = outer.getInputStream(
                    outer.getJarEntry("BOOT-INF/lib/dependency.jar")).readAllBytes();
            try (JarInputStream input = new JarInputStream(new ByteArrayInputStream(nestedJar))) {
                JarEntry entry;
                while ((entry = input.getNextJarEntry()) != null) {
                    if (entry.getName().equals("sample/NestedFlow.class")) return input.readAllBytes();
                }
            }
        }
        throw new AssertionError("Nested class was not repacked");
    }

    private static final class ByteClassLoader extends ClassLoader {
        private Class<?> define(byte[] bytecode) {
            return defineClass(null, bytecode, 0, bytecode.length);
        }
    }
}
