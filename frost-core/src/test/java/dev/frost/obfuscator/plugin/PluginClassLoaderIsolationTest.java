package dev.frost.obfuscator.plugin;

import dev.frost.obfuscator.transformer.Transformer;
import example.dep.VersionedDependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginClassLoaderIsolationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void eachPluginUsesItsOwnBundledDependencyInsteadOfHostOrSiblingVersions() throws Exception {
        assertEquals("host", VersionedDependency.version());
        Path firstPlugin = compilePluginJar("one");
        Path secondPlugin = compilePluginJar("two");
        List<Transformer> registered = new ArrayList<>();

        try (PluginLoader loader = new PluginLoader()) {
            assertTrue(loader.loadPlugin(firstPlugin, registered::add, registered::remove).isPresent());
            assertTrue(loader.loadPlugin(secondPlugin, registered::add, registered::remove).isPresent());
            assertEquals(Set.of("isolation:one", "isolation:two"),
                    registered.stream().map(Transformer::getName).collect(java.util.stream.Collectors.toSet()));

            assertTrue(loader.unloadPlugin(firstPlugin));
            assertEquals(List.of("isolation:two"), registered.stream().map(Transformer::getName).toList());
            assertTrue(loader.unloadPlugin(secondPlugin));
            assertTrue(registered.isEmpty());
        }
    }

    private Path compilePluginJar(String dependencyVersion) throws Exception {
        Path buildRoot = Files.createDirectories(temporaryDirectory.resolve(dependencyVersion));
        Path sourceRoot = Files.createDirectories(buildRoot.resolve("src"));
        Path classes = Files.createDirectories(buildRoot.resolve("classes"));
        Path dependencySource = writeSource(sourceRoot, "example/dep/VersionedDependency.java", """
                package example.dep;
                public final class VersionedDependency {
                    private VersionedDependency() { }
                    public static String version() { return "%s"; }
                }
                """.formatted(dependencyVersion));
        Path pluginSource = writeSource(sourceRoot, "example/plugin/IsolatedPlugin.java", """
                package example.plugin;
                import dev.frost.api.FrostPlugin;
                import dev.frost.api.PluginContext;
                import dev.frost.api.transformer.PluginTransformer;
                import dev.frost.api.transformer.TransformerContext;
                import example.dep.VersionedDependency;
                public final class IsolatedPlugin implements FrostPlugin {
                    @Override public void onLoad(PluginContext context) {
                        context.registerTransformer(new PluginTransformer() {
                            @Override public String id() {
                                return "isolation:" + VersionedDependency.version();
                            }
                            @Override public String name() { return "Isolation test"; }
                            @Override public void transform(TransformerContext context) { }
                        });
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests require a JDK compiler");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjects(dependencySource.toFile(), pluginSource.toFile());
            List<String> options = List.of("-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString(), "--release", "17");
            assertTrue(compiler.getTask(null, files, null, options, null, units).call());
        }

        Path jar = temporaryDirectory.resolve("isolated-plugin-" + dependencyVersion + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var compiled = Files.walk(classes)) {
                for (Path file : compiled.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName = classes.relativize(file).toString().replace('\\', '/');
                    output.putNextEntry(new JarEntry(entryName));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
            output.putNextEntry(new JarEntry("frost-plugin.yml"));
            output.write(("name: IsolatedPlugin\nversion: 1.0.0\n"
                    + "main: example.plugin.IsolatedPlugin\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private static Path writeSource(Path root, String relativePath, String source) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }
}
