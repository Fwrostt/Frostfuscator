package dev.frost.ir.plugin;

import dev.frost.ir.core.IrContext;
import java.util.Comparator;
import java.util.Objects;
import java.util.ServiceLoader;

public final class IrPlugins {
    private IrPlugins() {}

    /** Loads plugins deterministically by plugin id; each install is transactional. */
    public static void load(IrContext.Builder builder, ClassLoader loader) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(loader, "loader");
        ServiceLoader.load(IrPlugin.class, loader).stream().map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparing(plugin -> plugin.descriptor().id()))
                .forEach(builder::install);
    }
}
