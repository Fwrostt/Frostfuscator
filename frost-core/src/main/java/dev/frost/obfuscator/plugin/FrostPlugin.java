package dev.frost.obfuscator.plugin;

/**
 * Optional plugin entrypoint loaded from frost-plugin.yml.
 */
public interface FrostPlugin {
    void onLoad(PluginContext context);
}
