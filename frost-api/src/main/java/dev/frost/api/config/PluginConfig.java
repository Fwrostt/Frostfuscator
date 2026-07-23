package dev.frost.api.config;

import java.util.*;

/**
 * Type-safe configuration wrapper for plugins.
 */
public final class PluginConfig {

    private final Map<String, Object> values;

    public PluginConfig(Map<String, Object> values) {
        this.values = values != null ? values : Map.of();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = values.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    public String getString(String key, String defaultValue) {
        Object val = values.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object val = values.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object val = values.get(key);
        if (val instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (val instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public PluginConfig getSection(String key) {
        Object val = values.get(key);
        if (val instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((k, v) -> converted.put(String.valueOf(k), v));
            return new PluginConfig(converted);
        }
        return new PluginConfig(Map.of());
    }

    public Map<String, Object> raw() {
        return Collections.unmodifiableMap(values);
    }
}
