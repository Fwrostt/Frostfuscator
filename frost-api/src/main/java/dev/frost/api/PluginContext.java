package dev.frost.api;

import dev.frost.api.decompiler.CustomDecompilerProvider;
import dev.frost.api.encryption.StringEncryptorPlugin;
import dev.frost.api.event.EventBus;
import dev.frost.api.gui.UiExtensionPoint;
import dev.frost.api.graph.*;
import dev.frost.api.remapper.NameGeneratorPlugin;
import dev.frost.api.transformer.PluginTransformer;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Context provided to plugins during loading and lifecycle events.
 * Gives full access to event pub-sub, custom transformer registration, custom decompilers, string encryptors, name generators, UI extensions, and config.
 */
public class PluginContext {

    private final PluginDescriptor descriptor;
    private final Path pluginDirectory;
    private final PluginLogger logger;
    private final EventBus eventBus;

    private final List<PluginTransformer> transformers = new CopyOnWriteArrayList<>();
    private final List<CustomDecompilerProvider> decompilers = new CopyOnWriteArrayList<>();
    private final List<UiExtensionPoint> uiExtensions = new CopyOnWriteArrayList<>();
    private final List<StringEncryptorPlugin> stringEncryptors = new CopyOnWriteArrayList<>();
    private final List<NameGeneratorPlugin> nameGenerators = new CopyOnWriteArrayList<>();
    private final List<CustomGraphBuilder> graphBuilders = new CopyOnWriteArrayList<>();
    private final List<GraphMetadataProvider> graphMetadataProviders = new CopyOnWriteArrayList<>();
    private final List<GraphFilter> graphFilters = new CopyOnWriteArrayList<>();
    private final List<GraphContextAction> graphContextActions = new CopyOnWriteArrayList<>();
    private final List<GraphExportType> graphExportTypes = new CopyOnWriteArrayList<>();
    private final Map<String, Object> configMap = new ConcurrentHashMap<>();

    public PluginContext(PluginDescriptor descriptor, Path pluginDirectory, PluginLogger logger, EventBus eventBus) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.pluginDirectory = pluginDirectory;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    public PluginDescriptor descriptor() {
        return descriptor;
    }

    public Path pluginDirectory() {
        return pluginDirectory;
    }

    public PluginLogger logger() {
        return logger;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    /**
     * Registers a custom obfuscation transformer.
     */
    public void registerTransformer(PluginTransformer transformer) {
        Objects.requireNonNull(transformer, "transformer");
        transformers.add(transformer);
        logger.info("Registered plugin transformer: {} ({})", transformer.name(), transformer.id());
    }

    public List<PluginTransformer> registeredTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    /**
     * Registers a custom decompiler provider.
     */
    public void registerDecompiler(CustomDecompilerProvider decompiler) {
        Objects.requireNonNull(decompiler, "decompiler");
        decompilers.add(decompiler);
        logger.info("Registered plugin decompiler: {} v{}", decompiler.name(), decompiler.version());
    }

    public List<CustomDecompilerProvider> registeredDecompilers() {
        return Collections.unmodifiableList(decompilers);
    }

    /**
     * Registers a custom UI extension point.
     */
    public void registerUiExtension(UiExtensionPoint extension) {
        Objects.requireNonNull(extension, "extension");
        uiExtensions.add(extension);
        logger.info("Registered GUI extension: {} ({})", extension.label(), extension.type());
    }

    public List<UiExtensionPoint> registeredUiExtensions() {
        return Collections.unmodifiableList(uiExtensions);
    }

    /**
     * Registers a custom string encryptor plugin.
     */
    public void registerStringEncryptor(StringEncryptorPlugin encryptor) {
        Objects.requireNonNull(encryptor, "encryptor");
        stringEncryptors.add(encryptor);
        logger.info("Registered string encryptor plugin: {}", encryptor.id());
    }

    public List<StringEncryptorPlugin> registeredStringEncryptors() {
        return Collections.unmodifiableList(stringEncryptors);
    }

    /**
     * Registers a custom symbol name generator plugin.
     */
    public void registerNameGenerator(NameGeneratorPlugin generator) {
        Objects.requireNonNull(generator, "generator");
        nameGenerators.add(generator);
        logger.info("Registered name generator plugin: {}", generator.id());
    }

    public List<NameGeneratorPlugin> registeredNameGenerators() {
        return Collections.unmodifiableList(nameGenerators);
    }

    public void registerGraphBuilder(CustomGraphBuilder builder) {
        graphBuilders.add(Objects.requireNonNull(builder, "builder"));
        logger.info("Registered graph builder: {} ({})", builder.displayName(), builder.id());
    }
    public List<CustomGraphBuilder> registeredGraphBuilders() { return List.copyOf(graphBuilders); }

    public void registerGraphMetadataProvider(GraphMetadataProvider provider) {
        graphMetadataProviders.add(Objects.requireNonNull(provider, "provider"));
    }
    public List<GraphMetadataProvider> registeredGraphMetadataProviders() { return List.copyOf(graphMetadataProviders); }

    public void registerGraphFilter(GraphFilter filter) {
        graphFilters.add(Objects.requireNonNull(filter, "filter"));
        logger.info("Registered graph filter: {} ({})", filter.displayName(), filter.id());
    }
    public List<GraphFilter> registeredGraphFilters() { return List.copyOf(graphFilters); }

    public void registerGraphContextAction(GraphContextAction action) {
        graphContextActions.add(Objects.requireNonNull(action, "action"));
    }
    public List<GraphContextAction> registeredGraphContextActions() { return List.copyOf(graphContextActions); }

    public void registerGraphExportType(GraphExportType exportType) {
        graphExportTypes.add(Objects.requireNonNull(exportType, "exportType"));
        logger.info("Registered graph export type: {} ({})", exportType.displayName(), exportType.format());
    }
    public List<GraphExportType> registeredGraphExportTypes() { return List.copyOf(graphExportTypes); }

    public Map<String, Object> config() {
        return configMap;
    }

    public void setConfigProperty(String key, Object value) {
        if (key != null && value != null) {
            configMap.put(key, value);
        }
    }
}
