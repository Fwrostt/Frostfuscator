package dev.frost.api;

/**
 * Logging facade interface for Frostfuscator plugins.
 */
public interface PluginLogger {

    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);

    void debug(String message, Object... args);

    void trace(String message, Object... args);
}
