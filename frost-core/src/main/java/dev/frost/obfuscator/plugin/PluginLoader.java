package dev.frost.obfuscator.plugin;

import dev.frost.api.event.EventBus;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.util.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Owns dynamically loaded plugin class loaders and their complete unload lifecycle. */
public final class PluginLoader implements AutoCloseable {
    private static final EventBus GLOBAL_EVENT_BUS = new EventBus((listener, event, failure) ->
            Logger.error("Plugin event listener " + listener + " failed while handling "
                    + event.getClass().getName(), failure));

    private final Map<Path, PluginHandle> loadedPlugins = new LinkedHashMap<>();

    public static EventBus globalEventBus() {
        return GLOBAL_EVENT_BUS;
    }

    public List<PluginDescriptor> loadDirectories(List<Path> directories,
                                                  Consumer<Transformer> transformerRegistrar) {
        return loadDirectories(directories, transformerRegistrar, ignored -> { }).stream()
                .map(LoadedPlugin::descriptor).toList();
    }

    public synchronized List<LoadedPlugin> loadDirectories(List<Path> directories,
                                                           Consumer<Transformer> transformerRegistrar,
                                                           Consumer<Transformer> transformerUnregistrar) {
        List<LoadedPlugin> loaded = new ArrayList<>();
        Set<Path> seenJars = new LinkedHashSet<>();
        for (Path directory : directories) {
            if (directory == null || !Files.isDirectory(directory)) continue;
            try (var stream = Files.list(directory)) {
                for (Path jar : stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted().toList()) {
                    Path normalized = jar.toAbsolutePath().normalize();
                    if (seenJars.add(normalized) && !loadedPlugins.containsKey(normalized)) {
                        loadPlugin(normalized, transformerRegistrar, transformerUnregistrar).ifPresent(loaded::add);
                    }
                }
            } catch (IOException exception) {
                Logger.warn("Failed to scan plugin directory {}: {}", directory, exception.getMessage());
            }
        }
        return List.copyOf(loaded);
    }

    public synchronized Optional<LoadedPlugin> loadPlugin(Path jarPath,
                                                          Consumer<Transformer> transformerRegistrar,
                                                          Consumer<Transformer> transformerUnregistrar) {
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(transformerRegistrar, "transformerRegistrar");
        Objects.requireNonNull(transformerUnregistrar, "transformerUnregistrar");
        Path normalized = jarPath.toAbsolutePath().normalize();
        PluginHandle existing = loadedPlugins.get(normalized);
        if (existing != null) return Optional.of(existing.info);

        PluginClassLoader loader = null;
        dev.frost.api.FrostPlugin apiPlugin = null;
        dev.frost.api.PluginContext apiContext = null;
        FrostPlugin legacyPlugin = null;
        PluginContext legacyContext = null;
        List<Transformer> registered = new ArrayList<>();
        List<Transformer> applied = new ArrayList<>();
        try {
            loader = new PluginClassLoader(normalized.toUri().toURL(), PluginLoader.class.getClassLoader());
            PluginDescriptor descriptor = readDescriptor(normalized);

            ServiceLoader.load(Transformer.class, loader).forEach(registered::add);
            for (dev.frost.api.transformer.PluginTransformer transformer
                    : ServiceLoader.load(dev.frost.api.transformer.PluginTransformer.class, loader)) {
                registered.add(new PluginTransformerAdapter(transformer));
            }

            if (!descriptor.main().isBlank()) {
                Object plugin = Class.forName(descriptor.main(), true, loader).getDeclaredConstructor().newInstance();
                if (plugin instanceof dev.frost.api.FrostPlugin modernPlugin) {
                    apiPlugin = modernPlugin;
                    apiContext = new dev.frost.api.PluginContext(
                            new dev.frost.api.PluginDescriptor(descriptor.name(), descriptor.version(),
                                    descriptor.main(), descriptor.description(), descriptor.authors(), List.of()),
                            normalized.getParent(), logger(descriptor), GLOBAL_EVENT_BUS);
                    apiPlugin.onLoad(apiContext);
                    apiPlugin.onEnable(apiContext);
                    GLOBAL_EVENT_BUS.registerListener(apiPlugin);
                    apiContext.registeredTransformers().stream()
                            .map(PluginTransformerAdapter::new).forEach(registered::add);
                } else if (plugin instanceof FrostPlugin oldPlugin) {
                    legacyPlugin = oldPlugin;
                    legacyContext = new PluginContext(descriptor, normalized, registered::add);
                    legacyPlugin.onLoad(legacyContext);
                } else {
                    throw new IllegalArgumentException(descriptor.main() + " does not implement FrostPlugin");
                }
            }

            for (Transformer transformer : registered) {
                transformerRegistrar.accept(transformer);
                applied.add(transformer);
            }
            LoadedPlugin info = new LoadedPlugin(normalized, descriptor, registered.size(), Instant.now());
            loadedPlugins.put(normalized, new PluginHandle(info, loader, List.copyOf(registered),
                    transformerUnregistrar, apiPlugin, apiContext, legacyPlugin, legacyContext));
            Logger.info("Loaded plugin {} v{} from {} ({} transformer{})", descriptor.name(),
                    descriptor.version(), normalized.getFileName(), registered.size(), registered.size() == 1 ? "" : "s");
            return Optional.of(info);
        } catch (Throwable failure) {
            for (Transformer transformer : applied.reversed()) safely(() -> transformerUnregistrar.accept(transformer));
            dev.frost.api.FrostPlugin failedPlugin = apiPlugin;
            dev.frost.api.PluginContext failedContext = apiContext;
            if (failedPlugin != null) {
                safely(() -> failedPlugin.onDisable(failedContext));
                GLOBAL_EVENT_BUS.unregister(failedPlugin);
            }
            FrostPlugin failedLegacy = legacyPlugin;
            PluginContext failedLegacyContext = legacyContext;
            if (failedLegacy != null) safely(() -> failedLegacy.onUnload(failedLegacyContext));
            if (loader != null) {
                PluginClassLoader failedLoader = loader;
                GLOBAL_EVENT_BUS.unregisterClassLoader(failedLoader);
                safely(failedLoader::close);
            }
            Logger.warn("Failed to load plugin {}: {}", normalized, failure.getMessage());
            return Optional.empty();
        }
    }

    public synchronized Optional<LoadedPlugin> reloadPlugin(Path jarPath,
                                                            Consumer<Transformer> transformerRegistrar,
                                                            Consumer<Transformer> transformerUnregistrar) {
        Path normalized = jarPath.toAbsolutePath().normalize();
        unloadPlugin(normalized);
        return loadPlugin(normalized, transformerRegistrar, transformerUnregistrar);
    }

    public synchronized boolean unloadPlugin(Path jarPath) {
        if (jarPath == null) return false;
        PluginHandle handle = loadedPlugins.remove(jarPath.toAbsolutePath().normalize());
        if (handle == null) return false;

        if (handle.apiPlugin != null) {
            safely(() -> handle.apiPlugin.onDisable(handle.apiContext));
            GLOBAL_EVENT_BUS.unregister(handle.apiPlugin);
        }
        if (handle.legacyPlugin != null) safely(() -> handle.legacyPlugin.onUnload(handle.legacyContext));
        GLOBAL_EVENT_BUS.unregisterClassLoader(handle.loader);
        for (Transformer transformer : handle.transformers.reversed()) {
            safely(() -> handle.transformerUnregistrar.accept(transformer));
        }
        safely(handle.loader::close);
        Logger.info("Unloaded plugin {} v{}", handle.info.descriptor().name(), handle.info.descriptor().version());
        return true;
    }

    public synchronized List<LoadedPlugin> loadedPlugins() {
        return loadedPlugins.values().stream().map(handle -> handle.info).toList();
    }

    @Override
    public synchronized void close() {
        for (Path path : new ArrayList<>(loadedPlugins.keySet()).reversed()) unloadPlugin(path);
    }

    @SuppressWarnings("unchecked")
    private PluginDescriptor readDescriptor(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String name : List.of("frost-plugin.yml", "frost-plugin.yaml")) {
                JarEntry entry = jar.getJarEntry(name);
                if (entry == null) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    Object raw = new Yaml().load(input);
                    if (raw instanceof Map<?, ?> map) {
                        return new PluginDescriptor(string(map, "name", jarPath.getFileName().toString()),
                                string(map, "version", "0.0.0"), string(map, "main", ""),
                                string(map, "description", ""), stringList(map.get("authors")),
                                stringList(map.get("transformers")));
                    }
                }
            }
            if (jar.getEntry("META-INF/services/" + Transformer.class.getName()) != null
                    || jar.getEntry("META-INF/services/"
                    + dev.frost.api.transformer.PluginTransformer.class.getName()) != null) {
                return new PluginDescriptor(jarPath.getFileName().toString(), "0.0.0", "", "", List.of(), List.of());
            }
        }
        throw new IOException("missing frost-plugin.yml or PluginTransformer ServiceLoader provider");
    }

    private dev.frost.api.PluginLogger logger(PluginDescriptor descriptor) {
        return new dev.frost.api.PluginLogger() {
            @Override public void info(String message, Object... args) { Logger.info("[" + descriptor.name() + "] " + message, args); }
            @Override public void warn(String message, Object... args) { Logger.warn("[" + descriptor.name() + "] " + message, args); }
            @Override public void error(String message, Object... args) { Logger.error("[" + descriptor.name() + "] " + message, args); }
            @Override public void debug(String message, Object... args) { Logger.debug("[" + descriptor.name() + "] " + message, args); }
            @Override public void trace(String message, Object... args) { Logger.debug("[" + descriptor.name() + "] " + message, args); }
        };
    }

    private static String string(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value.toString();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(Object::toString).toList();
        if (value instanceof String string && !string.isBlank()) {
            return Arrays.stream(string.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
        }
        return List.of();
    }

    private static void safely(ThrowingAction action) {
        try { action.run(); } catch (Throwable failure) { Logger.warn("Plugin cleanup failed: {}", failure.getMessage()); }
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    private record PluginHandle(LoadedPlugin info, PluginClassLoader loader, List<Transformer> transformers,
                                Consumer<Transformer> transformerUnregistrar,
                                dev.frost.api.FrostPlugin apiPlugin, dev.frost.api.PluginContext apiContext,
                                FrostPlugin legacyPlugin, PluginContext legacyContext) { }
}
