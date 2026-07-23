package dev.frost.api.event.events;

import dev.frost.api.event.Cancellable;

import java.nio.file.Path;
import java.util.Map;

/**
 * Fired after all obfuscation passes complete, before writing output bytes to JAR.
 */
public final class PostObfuscationEvent implements Cancellable {

    private final Path outputJarPath;
    private final Map<String, byte[]> outputClassPool;
    private final Map<String, byte[]> outputResourcePool;
    private boolean cancelled;

    public PostObfuscationEvent(Path outputJarPath, Map<String, byte[]> outputClassPool, Map<String, byte[]> outputResourcePool) {
        this.outputJarPath = outputJarPath;
        this.outputClassPool = outputClassPool;
        this.outputResourcePool = outputResourcePool;
    }

    public Path outputJarPath() {
        return outputJarPath;
    }

    public Map<String, byte[]> outputClassPool() {
        return outputClassPool;
    }

    public Map<String, byte[]> outputResourcePool() {
        return outputResourcePool;
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
