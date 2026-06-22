package dev.frost.obfuscator.plugin;

import dev.frost.obfuscator.transformer.Transformer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

public final class PluginContext {
    private final PluginDescriptor descriptor;
    private final Path jarPath;
    private final Consumer<Transformer> transformerRegistrar;

    public PluginContext(PluginDescriptor descriptor, Path jarPath, Consumer<Transformer> transformerRegistrar) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.jarPath = Objects.requireNonNull(jarPath, "jarPath");
        this.transformerRegistrar = Objects.requireNonNull(transformerRegistrar, "transformerRegistrar");
    }

    public PluginDescriptor descriptor() {
        return descriptor;
    }

    public Path jarPath() {
        return jarPath;
    }

    public void registerTransformer(Transformer transformer) {
        transformerRegistrar.accept(Objects.requireNonNull(transformer, "transformer"));
    }
}
