package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.util.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildCancellationIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void activeEngineBuildCancelsPromptlyAndKeepsPreviousOutput() throws Exception {
        Path input = tempDir.resolve("input.jar");
        Path output = tempDir.resolve("output.jar");
        byte[] previousOutput = "previous-good-output".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previousOutput);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            for (int index = 0; index < 1_000; index++) {
                jar.putNextEntry(new JarEntry("app/C" + index + ".class"));
                jar.write(classWithMethod("app/C" + index));
                jar.closeEntry();
            }
        }

        ObfuscationConfig config = new ObfuscationConfig();
        config.setInput(input.toString());
        config.setOutput(output.toString());
        config.getLibraries().setRuntime(false);
        config.getLibraries().setAutoDetect(false);
        config.getMapping().setEnabled(false);
        BuildCancellation cancellation = new BuildCancellation();
        CountDownLatch transformerStarted = new CountDownLatch(1);
        Consumer<String> listener = line -> {
            if (line.contains("Running transformer: method-rename")) transformerStarted.countDown();
        };
        Logger.addListener(listener);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProtectionStats> build = executor.submit(
                    () -> new ObfuscationEngine(config, List.of("method-rename"), cancellation).run());
            assertTrue(transformerStarted.await(10, TimeUnit.SECONDS));
            cancellation.cancel();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> build.get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
            assertArrayEquals(previousOutput, Files.readAllBytes(output));
        } finally {
            Logger.removeListener(listener);
            executor.shutdownNow();
        }
    }

    private static byte[] classWithMethod(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "work", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
