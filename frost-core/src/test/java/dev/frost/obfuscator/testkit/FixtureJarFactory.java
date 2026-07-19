package dev.frost.obfuscator.testkit;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class FixtureJarFactory {
    public static final String MAIN_CLASS = "com.acme.frostfixture.FixtureApp";
    public static final String PLUGIN_MAIN_CLASS = "com.acme.frostfixture.PluginEntrypoint";

    private FixtureJarFactory() {
    }

    public static Path buildMulticlassJar(Path tempDir) throws IOException {
        Path fixtureRoot = Path.of("src/test/fixtures/multiclass");
        Path sourceRoot = fixtureRoot.resolve("src/main/java");
        Path resourceRoot = fixtureRoot.resolve("src/main/resources");
        Path classesDir = tempDir.resolve("fixture-classes");
        Path outputJar = tempDir.resolve("frost-multiclass-fixture.jar");
        Files.createDirectories(classesDir);

        compileSources(sourceRoot, classesDir);
        writeJar(classesDir, resourceRoot, outputJar);
        return outputJar;
    }

    private static void compileSources(Path sourceRoot, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests must run on a JDK with javac, not a JRE");
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of("--release", "17", "-d", classesDir.toString());
            Boolean ok = compiler.getTask(null, files, null, options, null,
                    files.getJavaFileObjectsFromPaths(sources)).call();
            assertEquals(Boolean.TRUE, ok, "Fixture source compilation failed");
        }
    }

    private static void writeJar(Path classesDir, Path resourceRoot, Path outputJar) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, MAIN_CLASS);

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outputJar), manifest)) {
            addTree(jar, classesDir, classesDir);
            addTree(jar, resourceRoot, resourceRoot);
            addGeneratedDatabase(jar);
        }
    }

    private static void addTree(JarOutputStream jar, Path root, Path base) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        for (Path file : files) {
            String entryName = base.relativize(file).toString().replace('\\', '/');
            addEntry(jar, entryName, Files.readAllBytes(file));
        }
    }

    private static void addGeneratedDatabase(JarOutputStream jar) throws IOException {
        byte[] pattern = "FROST-FIXTURE-DATABASE-0123456789\n".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[128 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = pattern[i % pattern.length];
        }
        addEntry(jar, "data/database.bin", data);
    }

    private static void addEntry(JarOutputStream jar, String name, byte[] data) throws IOException {
        JarEntry entry = new JarEntry(name);
        jar.putNextEntry(entry);
        jar.write(data);
        jar.closeEntry();
    }

    public static List<String> classEntries(Path jarPath) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            List<String> entries = new ArrayList<>();
            jar.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.endsWith(".class"))
                    .sorted()
                    .forEach(entries::add);
            return entries;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
