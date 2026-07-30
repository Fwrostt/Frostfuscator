package dev.frost.obfuscator.remapper;

import java.util.Locale;

public enum MappingFormat {
    YAML("yaml", "mapping.yml"),
    PROGUARD("proguard", "mapping.txt"),
    TINY("tiny", "mapping.tiny");

    private final String id;
    private final String defaultFileName;

    MappingFormat(String id, String defaultFileName) {
        this.id = id;
        this.defaultFileName = defaultFileName;
    }

    public String id() {
        return id;
    }

    public String defaultFileName() {
        return defaultFileName;
    }

    public static MappingFormat parse(String value) {
        String normalized = value == null ? "yaml" : value.trim().toLowerCase(Locale.ROOT);
        for (MappingFormat format : values()) {
            if (format.id.equals(normalized)) return format;
        }
        throw new IllegalArgumentException("Unsupported mapping format '" + value
                + "'. Use yaml, proguard, or tiny.");
    }
}
