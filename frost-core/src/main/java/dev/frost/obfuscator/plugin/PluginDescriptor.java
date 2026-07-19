package dev.frost.obfuscator.plugin;

import java.util.List;

public record PluginDescriptor(
        String name,
        String version,
        String main,
        String description,
        List<String> authors,
        List<String> transformers
) {
    public PluginDescriptor {
        name = blankDefault(name, "unknown-plugin");
        version = blankDefault(version, "0.0.0");
        description = description == null ? "" : description;
        main = main == null ? "" : main;
        authors = authors == null ? List.of() : List.copyOf(authors);
        transformers = transformers == null ? List.of() : List.copyOf(transformers);
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
