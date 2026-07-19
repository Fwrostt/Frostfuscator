package dev.frost.obfuscator.plugin;

import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.util.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;

public final class PluginLoader {
    private static final List<ClassLoader> ACTIVE_LOADERS = new ArrayList<>();

    public List<PluginDescriptor> loadDirectories(List<Path> directories, Consumer<Transformer> transformerRegistrar) {
        List<PluginDescriptor> loaded = new ArrayList<>();
        Set<Path> seenJars = new LinkedHashSet<>();
        for (Path directory : directories) {
            if (directory == null || !Files.isDirectory(directory)) {
                continue;
            }
            try (var stream = Files.list(directory)) {
                for (Path jar : stream
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted()
                        .toList()) {
                    Path normalized = jar.toAbsolutePath().normalize();
                    if (seenJars.add(normalized)) {
                        loadJar(normalized, transformerRegistrar).ifPresent(loaded::add);
                    }
                }
            } catch (IOException exception) {
                Logger.warn("Failed to scan plugin directory {}: {}", directory, exception.getMessage());
            }
        }
        return loaded;
    }

    private java.util.Optional<PluginDescriptor> loadJar(Path jarPath, Consumer<Transformer> transformerRegistrar) {
        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{jarPath.toUri().toURL()},
                    PluginLoader.class.getClassLoader()
            );
            ACTIVE_LOADERS.add(loader);

            PluginDescriptor descriptor = readDescriptor(jarPath, loader);
            int registered = 0;
            ServiceLoader<Transformer> serviceLoader = ServiceLoader.load(Transformer.class, loader);
            for (Transformer transformer : serviceLoader) {
                transformerRegistrar.accept(transformer);
                registered++;
            }

            if (!descriptor.main().isBlank()) {
                Class<?> type = Class.forName(descriptor.main(), true, loader);
                Object plugin = type.getDeclaredConstructor().newInstance();
                if (!(plugin instanceof FrostPlugin frostPlugin)) {
                    throw new IllegalArgumentException(descriptor.main() + " does not implement FrostPlugin");
                }
                frostPlugin.onLoad(new PluginContext(descriptor, jarPath, transformerRegistrar));
            }

            Logger.info("Loaded plugin {} v{} from {} ({} service transformer{})",
                    descriptor.name(),
                    descriptor.version(),
                    jarPath.getFileName(),
                    registered,
                    registered == 1 ? "" : "s");
            return java.util.Optional.of(descriptor);
        } catch (Exception exception) {
            Logger.warn("Failed to load plugin {}: {}", jarPath, exception.getMessage());
            return java.util.Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private PluginDescriptor readDescriptor(Path jarPath, ClassLoader loader) throws IOException {
        String[] names = {"frost-plugin.yml", "frost-plugin.yaml"};
        for (String name : names) {
            try (InputStream input = loader.getResourceAsStream(name)) {
                if (input != null) {
                    Object raw = new Yaml().load(input);
                    if (raw instanceof Map<?, ?> map) {
                        return new PluginDescriptor(
                                string(map, "name", jarPath.getFileName().toString()),
                                string(map, "version", "0.0.0"),
                                string(map, "main", ""),
                                string(map, "description", ""),
                                stringList(map.get("authors")),
                                stringList(map.get("transformers"))
                        );
                    }
                }
            }
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.getEntry("META-INF/services/" + Transformer.class.getName()) != null) {
                return new PluginDescriptor(jarPath.getFileName().toString(), "0.0.0", "", "", List.of(), List.of());
            }
        }
        throw new IOException("missing frost-plugin.yml or Transformer ServiceLoader provider");
    }

    private String string(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value.toString();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (value instanceof String string && !string.isBlank()) {
            return java.util.Arrays.stream(string.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .toList();
        }
        return List.of();
    }
}
