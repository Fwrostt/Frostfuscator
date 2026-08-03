package dev.frost.ir.plugin;

/** ServiceLoader-friendly, context-local Frost-IR extension point. */
public interface IrPlugin {
    IrPluginDescriptor descriptor();
    void register(IrPluginRegistrar registrar);
}
