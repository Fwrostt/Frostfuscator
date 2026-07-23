package dev.frost.api;

import java.util.List;

/**
 * Metadata record describing a Frostfuscator plugin manifest.
 */
public record PluginDescriptor(
        String name,
        String version,
        String mainClass,
        String description,
        List<String> authors,
        List<String> dependencies,
        int priority
) {
    public PluginDescriptor {
        if (name == null || name.isBlank()) name = "UnnamedPlugin";
        if (version == null || version.isBlank()) version = "1.0.0";
        if (mainClass == null) mainClass = "";
        if (description == null) description = "";
        if (authors == null) authors = List.of();
        if (dependencies == null) dependencies = List.of();
    }

    public PluginDescriptor(String name, String version, String mainClass, String description, List<String> authors, List<String> dependencies) {
        this(name, version, mainClass, description, authors, dependencies, 0);
    }
}
