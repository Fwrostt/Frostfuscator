package dev.frost.obfuscator.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-plugin class loader that keeps Frost/JDK boundary types shared while resolving bundled
 * plugin dependencies before similarly named host dependencies.
 */
final class PluginClassLoader extends URLClassLoader {
    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "dev.frost.api.", "dev.frost.graph.", "dev.frost.obfuscator.transformer."
    );
    private static final Set<String> PARENT_FIRST_CLASSES = Set.of(
            "dev.frost.obfuscator.plugin.FrostPlugin",
            "dev.frost.obfuscator.plugin.PluginContext",
            "dev.frost.obfuscator.plugin.PluginDescriptor"
    );

    static {
        registerAsParallelCapable();
    }

    PluginClassLoader(URL pluginJar, ClassLoader parent) {
        super(new URL[]{pluginJar}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = isParentFirst(name) ? loadParentFirst(name) : loadPluginFirst(name);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        URL resource = findResource(name);
        return resource != null ? resource : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Set<URL> resources = new LinkedHashSet<>();
        findResources(name).asIterator().forEachRemaining(resources::add);
        ClassLoader parent = getParent();
        Enumeration<URL> parentResources = parent == null
                ? ClassLoader.getSystemResources(name)
                : parent.getResources(name);
        parentResources.asIterator().forEachRemaining(resources::add);
        return Collections.enumeration(resources);
    }

    private Class<?> loadPluginFirst(String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (ClassNotFoundException ignored) {
            return super.loadClass(name, false);
        }
    }

    private Class<?> loadParentFirst(String name) throws ClassNotFoundException {
        return super.loadClass(name, false);
    }

    private static boolean isParentFirst(String name) {
        return PARENT_FIRST_CLASSES.contains(name)
                || PARENT_FIRST_PREFIXES.stream().anyMatch(name::startsWith);
    }
}
