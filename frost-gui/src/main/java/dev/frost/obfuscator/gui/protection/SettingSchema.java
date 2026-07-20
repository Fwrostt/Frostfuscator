package dev.frost.obfuscator.gui.protection;

import java.util.List;

public record SettingSchema(
        String key,
        String label,
        String description,
        Type type,
        Object defaultValue,
        int min,
        int max,
        int step,
        String unit,
        List<String> choices,
        boolean advanced
) {
    public enum Type { BOOLEAN, INTEGER, CHOICE, TEXT }
}
