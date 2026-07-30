package dev.frost.obfuscator.gui.plugin;

import dev.frost.obfuscator.plugin.LoadedPlugin;
import dev.frost.obfuscator.transformer.TransformerRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes GUI plugin load/unload operations away from the JavaFX thread. */
public final class PluginRuntimeService implements AutoCloseable {
    private final Path pluginDirectory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "frost-plugin-runtime");
        thread.setDaemon(true);
        return thread;
    });

    public PluginRuntimeService(Path pluginDirectory) {
        this.pluginDirectory = pluginDirectory.toAbsolutePath().normalize();
    }

    public Path pluginDirectory() { return pluginDirectory; }

    public List<LoadedPlugin> loadedPlugins() { return TransformerRegistry.loadedPlugins(); }

    public CompletableFuture<List<LoadedPlugin>> scanDefaultDirectory() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(pluginDirectory);
                TransformerRegistry.discoverPlugins(List.of(pluginDirectory));
                return loadedPlugins();
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, executor);
    }

    public CompletableFuture<List<LoadedPlugin>> load(Path jarPath) {
        return CompletableFuture.supplyAsync(() -> {
            if (TransformerRegistry.loadPlugin(jarPath).isEmpty()) {
                throw new java.util.concurrent.CompletionException(
                        new IOException("Plugin could not be loaded. Check the application log for details."));
            }
            return loadedPlugins();
        }, executor);
    }

    public CompletableFuture<List<LoadedPlugin>> reload(Path jarPath) {
        return CompletableFuture.supplyAsync(() -> {
            if (TransformerRegistry.reloadPlugin(jarPath).isEmpty()) {
                throw new java.util.concurrent.CompletionException(
                        new IOException("Plugin could not be reloaded. Check the application log for details."));
            }
            return loadedPlugins();
        }, executor);
    }

    public CompletableFuture<List<LoadedPlugin>> unload(Path jarPath) {
        return CompletableFuture.supplyAsync(() -> {
            if (!TransformerRegistry.unloadPlugin(jarPath)) {
                throw new java.util.concurrent.CompletionException(new IOException("Plugin is no longer loaded."));
            }
            return loadedPlugins();
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        for (LoadedPlugin plugin : loadedPlugins().reversed()) {
            TransformerRegistry.unloadPlugin(plugin.jarPath());
        }
    }
}
