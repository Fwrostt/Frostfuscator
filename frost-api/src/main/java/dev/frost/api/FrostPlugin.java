package dev.frost.api;

/**
 * Entrypoint interface for Frostfuscator plugins.
 * Implementations are instantiated via ServiceLoader or declared in frost-plugin.yml.
 */
public interface FrostPlugin {

    /**
     * Called when the plugin is first loaded into memory.
     * Use this phase to register custom transformers, event listeners, decompilers, and GUI extensions.
     *
     * @param context the plugin context provided by Frostfuscator
     */
    void onLoad(PluginContext context);

    /**
     * Called when the plugin is enabled prior to obfuscation pipeline execution.
     *
     * @param context the plugin context provided by Frostfuscator
     */
    default void onEnable(PluginContext context) {}

    /**
     * Called when the plugin is disabled or Frostfuscator shuts down.
     *
     * @param context the plugin context provided by Frostfuscator
     */
    default void onDisable(PluginContext context) {}
}
