package dev.frost.obfuscator.plugin;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Immutable runtime view of a dynamically loaded plugin. */
public record LoadedPlugin(Path jarPath, PluginDescriptor descriptor, int transformerCount, Instant loadedAt) {
    public LoadedPlugin {
        jarPath = Objects.requireNonNull(jarPath, "jarPath").toAbsolutePath().normalize();
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        loadedAt = Objects.requireNonNull(loadedAt, "loadedAt");
    }
}
