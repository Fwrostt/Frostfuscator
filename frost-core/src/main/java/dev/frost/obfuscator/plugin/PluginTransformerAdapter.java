package dev.frost.obfuscator.plugin;

import dev.frost.api.PluginLogger;
import dev.frost.api.transformer.ExecutionPass;
import dev.frost.api.transformer.PluginTransformer;
import dev.frost.api.transformer.TransformerContext;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.Objects;

/**
 * Adapter wrapping a public API PluginTransformer as a core Transformer for the ObfuscationEngine.
 */
public final class PluginTransformerAdapter extends Transformer {

    private final PluginTransformer apiTransformer;

    public PluginTransformerAdapter(PluginTransformer apiTransformer) {
        this.apiTransformer = Objects.requireNonNull(apiTransformer, "apiTransformer");
    }

    public PluginTransformer getApiTransformer() {
        return apiTransformer;
    }

    @Override
    public String getName() {
        return apiTransformer.name();
    }

    @Override
    public String getCategory() {
        return apiTransformer.category().name();
    }

    @Override
    public Priority priority() {
        if (apiTransformer.pass() == ExecutionPass.PRE_PROCESSING) {
            return Priority.PRE_OBFUSCATION;
        } else if (apiTransformer.pass() == ExecutionPass.POST_PROCESSING) {
            return Priority.POST_REMAP;
        } else if (apiTransformer.pass() == ExecutionPass.FINALIZATION) {
            return Priority.FINAL;
        }
        return Priority.NORMAL;
    }

    @Override
    public void transform(Context context) {
        TransformerContext apiContext = new TransformerContext() {
            @Override
            public Map<String, ClassNode> classPool() {
                return context.pool().getClassMap();
            }

            @Override
            public Map<String, byte[]> resourcePool() {
                return context.resources();
            }

            @Override
            public PluginLogger logger() {
                return new PluginLogger() {
                    @Override
                    public void info(String message, Object... args) {
                        Logger.info("[" + getName() + "] " + message, args);
                    }

                    @Override
                    public void warn(String message, Object... args) {
                        Logger.warn("[" + getName() + "] " + message, args);
                    }

                    @Override
                    public void error(String message, Object... args) {
                        Logger.error("[" + getName() + "] " + message, args);
                    }

                    @Override
                    public void debug(String message, Object... args) {
                        Logger.debug("[" + getName() + "] " + message, args);
                    }

                    @Override
                    public void trace(String message, Object... args) {
                        Logger.debug("[" + getName() + "] " + message, args);
                    }
                };
            }

            @Override
            public Map<String, Object> config() {
                return Map.of();
            }

            @Override
            public void addClass(ClassNode classNode) {
                if (classNode != null && classNode.name != null) {
                    context.pool().getClassMap().put(classNode.name, classNode);
                }
            }

            @Override
            public void removeClass(String internalName) {
                if (internalName != null) {
                    context.pool().getClassMap().remove(internalName);
                }
            }

            @Override
            public void addResource(String path, byte[] data) {
                if (path != null && data != null) {
                    context.resources().put(path, data);
                }
            }

            @Override
            public void removeResource(String path) {
                if (path != null) {
                    context.resources().remove(path);
                }
            }
        };

        apiTransformer.transform(apiContext);
    }
}
