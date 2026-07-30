package dev.frost.api.transformer;

import java.util.Map;
import java.util.Set;

/**
 * Interface for custom obfuscation transformers created by plugins.
 */
public interface PluginTransformer {

    /**
     * @return unique identifier string for this transformer (e.g. "my-plugin:custom-flow")
     */
    String id();

    /**
     * @return human-readable name of transformer
     */
    String name();

    /**
     * @return category of transformer
     */
    default TransformerCategory category() {
        return TransformerCategory.CUSTOM;
    }

    /**
     * @return execution pass order
     */
    default ExecutionPass pass() {
        return ExecutionPass.PRIMARY;
    }

    /**
     * Stable ordering inside {@link #pass()}. Lower weights execute first; equal weights retain
     * registration order.
     */
    default int orderWeight() {
        return 0;
    }

    default Set<String> dependencies() { return Set.of(); }

    default Set<String> conflicts() { return Set.of(); }

    /**
     * Checks if transformer is enabled based on configuration.
     */
    default boolean isEnabled(Map<String, Object> config) {
        return true;
    }

    /**
     * Executes transformer logic against the provided context.
     *
     * @param context the transformer execution context
     */
    void transform(TransformerContext context);
}
