package dev.frost.obfuscator.plugin;

import dev.frost.api.FrostPlugin;
import dev.frost.api.PluginContext;
import dev.frost.api.transformer.ExecutionPass;
import dev.frost.api.transformer.PluginTransformer;
import dev.frost.api.transformer.TransformerContext;
import dev.frost.obfuscator.transformer.Transformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginLoaderHotReloadTest {
    @TempDir Path temporaryDirectory;

    public static final class HotPlugin implements FrostPlugin {
        static final AtomicInteger loads = new AtomicInteger();
        static final AtomicInteger enables = new AtomicInteger();
        static final AtomicInteger disables = new AtomicInteger();

        @Override
        public void onLoad(PluginContext context) {
            loads.incrementAndGet();
            context.registerTransformer(new PluginTransformer() {
                @Override public String id() { return "test:hot-reload"; }
                @Override public String name() { return "Hot reload transformer"; }
                @Override public ExecutionPass pass() { return ExecutionPass.PRE_RENAME; }
                @Override public int orderWeight() { return -100; }
                @Override public void transform(TransformerContext context) { }
            });
        }

        @Override public void onEnable(PluginContext context) { enables.incrementAndGet(); }
        @Override public void onDisable(PluginContext context) { disables.incrementAndGet(); }
    }

    @Test
    void loadsReloadsAndUnloadsPluginLifecycleWithoutRestart() throws Exception {
        HotPlugin.loads.set(0);
        HotPlugin.enables.set(0);
        HotPlugin.disables.set(0);
        Path pluginJar = pluginJar();
        List<Transformer> registered = new ArrayList<>();

        try (PluginLoader loader = new PluginLoader()) {
            LoadedPlugin first = loader.loadPlugin(pluginJar, registered::add, registered::remove).orElseThrow();
            assertEquals("HotPlugin", first.descriptor().name());
            assertEquals(1, registered.size());
            assertEquals(Transformer.Priority.PRE_RENAME, registered.getFirst().priority());
            assertEquals(-100, registered.getFirst().orderWeight());

            loader.reloadPlugin(pluginJar, registered::add, registered::remove).orElseThrow();
            assertEquals(1, registered.size(), "reload must replace, not duplicate, transformers");
            assertEquals(2, HotPlugin.loads.get());
            assertEquals(2, HotPlugin.enables.get());
            assertEquals(1, HotPlugin.disables.get());

            assertTrue(loader.unloadPlugin(pluginJar));
            assertTrue(registered.isEmpty());
            assertEquals(2, HotPlugin.disables.get());
            assertTrue(loader.loadedPlugins().isEmpty());
        }

        Path moved = temporaryDirectory.resolve("hot-plugin-moved.jar");
        Files.move(pluginJar, moved);
        assertTrue(Files.isRegularFile(moved), "closed plugin JARs must be movable on Windows");
    }

    private Path pluginJar() throws Exception {
        Path jar = temporaryDirectory.resolve("hot-plugin.jar");
        String descriptor = "name: HotPlugin\nversion: 1.0.0\nmain: " + HotPlugin.class.getName() + "\n";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("frost-plugin.yml"));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
