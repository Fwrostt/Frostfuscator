package dev.frost.api.event.events;

import dev.frost.api.event.Cancellable;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.Map;

/**
 * Fired immediately before obfuscation pass begins.
 * Listeners can modify initial class pool, resource pool, configuration, or cancel obfuscation.
 */
public final class PreObfuscationEvent implements Cancellable {

    private final Path inputJarPath;
    private final Map<String, ClassNode> classPool;
    private final Map<String, byte[]> resourcePool;
    private final Map<String, Object> config;
    private boolean cancelled;

    public PreObfuscationEvent(Path inputJarPath, Map<String, ClassNode> classPool, Map<String, byte[]> resourcePool, Map<String, Object> config) {
        this.inputJarPath = inputJarPath;
        this.classPool = classPool;
        this.resourcePool = resourcePool;
        this.config = config;
    }

    public Path inputJarPath() {
        return inputJarPath;
    }

    public Map<String, ClassNode> classPool() {
        return classPool;
    }

    public Map<String, byte[]> resourcePool() {
        return resourcePool;
    }

    public Map<String, Object> config() {
        return config;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
