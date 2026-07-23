package dev.frost.api.event.events;

import java.util.Map;

/**
 * Fired when an obfuscation configuration profile is loaded.
 */
public final class ConfigLoadEvent {

    private final Map<String, Object> configMap;

    public ConfigLoadEvent(Map<String, Object> configMap) {
        this.configMap = configMap;
    }

    public Map<String, Object> configMap() {
        return configMap;
    }
}
