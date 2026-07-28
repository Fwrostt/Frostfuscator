package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.config.ObfuscationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LibraryDetectionTest {
    @TempDir
    Path tempDir;

    @Test
    void suppliedLibraryCopiesRemainInOutputPoolButAreNotTransformable() throws Exception {
        ClassPool pool = new ClassPool();
        pool.addClass("app/Main", node("app/Main"));
        pool.addClass("dependency/LibraryType", node("dependency/LibraryType"));

        Path library = tempDir.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(library))) {
            output.putNextEntry(new JarEntry("dependency/LibraryType.class"));
            output.write(classBytes("dependency/LibraryType"));
            output.closeEntry();
        }

        LibraryLoadReport report = new JarProcessor().loadLibraries(pool,
                new LibraryOptions(List.of(library), false, false, false));

        assertEquals(1, report.excludedInputClasses());
        assertTrue(pool.isTransformationExcluded("dependency/LibraryType"));
        assertTrue(pool.getClassMap().containsKey("dependency/LibraryType"));
        assertEquals(List.of("app/Main"), pool.getClasses().stream().map(value -> value.name).toList());
    }

    @Test
    void largeFatJarUsesEntrypointOwnershipToSkipShadedDependencies() {
        ClassPool pool = new ClassPool();
        pool.addClass("dev/frost/app/Main", node("dev/frost/app/Main"));
        pool.addClass("dev/frost/shared/Owned", node("dev/frost/shared/Owned"));
        for (int index = 0; index < 1_000; index++) {
            pool.addClass("thirdparty/bundle/C" + index, node("thirdparty/bundle/C" + index));
        }

        ApplicationClassDetector.DetectionResult result = new ApplicationClassDetector()
                .detect(pool, List.of("dev.frost.app.Main"));

        assertEquals(1_000, result.excludedClasses());
        assertEquals(2, pool.transformableSize());
        assertFalse(pool.isTransformationExcluded("dev/frost/shared/Owned"));
        assertTrue(pool.isTransformationExcluded("thirdparty/bundle/C999"));
    }

    @Test
    void cancelledWriteDoesNotReplaceAnExistingOutput() throws Exception {
        byte[] existing = "previous-good-output".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path output = tempDir.resolve("protected.jar");
        Files.write(output, existing);
        BuildCancellation cancellation = new BuildCancellation();
        JarProcessor processor = new JarProcessor(cancellation);
        ClassPool pool = new ClassPool();
        pool.setCancellation(cancellation);
        pool.addClass("app/Main", node("app/Main"));
        cancellation.cancel();

        assertThrows(CancellationException.class, () -> processor.writeJar(pool, output));
        assertArrayEquals(existing, Files.readAllBytes(output));
    }

    @Test
    void fullEnginePreservesSuppliedLibraryCopyByteForByte() throws Exception {
        byte[] applicationBytes = classBytes("app/Main");
        byte[] dependencyBytes = classBytes("dependency/LibraryType");
        Path input = tempDir.resolve("input.jar");
        Path library = tempDir.resolve("library.jar");
        Path output = tempDir.resolve("output.jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "app.Main");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input), manifest)) {
            writeEntry(jar, "app/Main.class", applicationBytes);
            writeEntry(jar, "dependency/LibraryType.class", dependencyBytes);
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library))) {
            writeEntry(jar, "dependency/LibraryType.class", dependencyBytes);
        }

        ObfuscationConfig config = new ObfuscationConfig();
        config.setInput(input.toString());
        config.setOutput(output.toString());
        config.getLibraries().setRuntime(false);
        config.getLibraries().setPaths(List.of(library.toString()));
        config.getMapping().setEnabled(false);
        new ObfuscationEngine(config, List.of("class-rename")).run();

        try (JarFile result = new JarFile(output.toFile())) {
            assertArrayEquals(dependencyBytes,
                    result.getInputStream(result.getJarEntry("dependency/LibraryType.class")).readAllBytes());
        }
    }

    private static void writeEntry(JarOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static ClassNode node(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private static byte[] classBytes(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
