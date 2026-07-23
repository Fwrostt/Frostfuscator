package dev.frost.obfuscator.transformer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TransformerConfig {

    private boolean enabled = true;
    private List<String> exclusions = new ArrayList<>();
    private List<String> inclusions = new ArrayList<>();
    private String dictionary = "alphabet";
    private Map<String, Object> options = new LinkedHashMap<>();

    public TransformerConfig() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getExclusions() {
        return exclusions;
    }

    public void setExclusions(List<String> exclusions) {
        this.exclusions = exclusions != null ? exclusions : new ArrayList<>();
    }

    public List<String> getInclusions() {
        return inclusions;
    }

    public void setInclusions(List<String> inclusions) {
        this.inclusions = inclusions != null ? inclusions : new ArrayList<>();
    }

    public String getDictionary() {
        return dictionary;
    }

    public void setDictionary(String dictionary) {
        this.dictionary = dictionary;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options != null ? options : new LinkedHashMap<>();
    }

    public String getOption(String key, String defaultValue) {
        Object val = options.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    public int getOptionInt(String key, int defaultValue) {
        Object val = options.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString().trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public long getOptionLong(String key, long defaultValue) {
        Object val = options.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val != null) {
            try { return Long.parseLong(val.toString().trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public boolean getOptionBoolean(String key, boolean defaultValue) {
        Object val = options.get(key);
        if (val instanceof Boolean b) return b;
        if (val != null) {
            return Boolean.parseBoolean(val.toString().trim());
        }
        return defaultValue;
    }
}
