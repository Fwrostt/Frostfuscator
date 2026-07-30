package dev.frost.obfuscator.gui.protection;

import dev.frost.obfuscator.transformer.TransformerConfig;

import java.util.*;

public record TransformerSchema(String transformer, List<SettingSchema> settings) {

    public static TransformerSchema infer(String transformer, TransformerConfig config, TransformerConfig recommended) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(recommended.getOptions().keySet());
        keys.addAll(config.getOptions().keySet());
        List<SettingSchema> settings = new ArrayList<>();
        for (String key : keys) {
            Object fallback = recommended.getOptions().getOrDefault(key, config.getOptions().get(key));
            Object value = config.getOptions().getOrDefault(key, fallback);
            boolean advanced = advanced(key);
            if (value instanceof Boolean || fallback instanceof Boolean) {
                settings.add(new SettingSchema(key, label(key), description(key), SettingSchema.Type.BOOLEAN,
                        booleanValue(fallback), 0, 1, 1, "", List.of(), advanced));
            } else if (value instanceof Number || fallback instanceof Number) {
                int initial = intValue(fallback, intValue(value, 0));
                Range range = range(key, initial);
                settings.add(new SettingSchema(key, label(key), description(key), SettingSchema.Type.INTEGER,
                        initial, range.min, range.max, range.step, unit(key), List.of(), advanced));
            } else {
                List<String> choices = choices(key, String.valueOf(fallback));
                SettingSchema.Type type = choices.isEmpty() ? SettingSchema.Type.TEXT : SettingSchema.Type.CHOICE;
                settings.add(new SettingSchema(key, label(key), description(key), type,
                        fallback == null ? "" : fallback, 0, 0, 1, "", choices, advanced));
            }
        }
        return new TransformerSchema(transformer, List.copyOf(settings));
    }

    private static boolean advanced(String key) {
        return key.contains("seed") || key.contains("budget") || key.contains("instructions")
                || key.contains("threshold") || key.startsWith("max-") || key.startsWith("min-")
                || key.contains("iterations") || key.contains("depth") || key.contains("rate");
    }

    private static List<String> choices(String key, String fallback) {
        return switch (key) {
            case "mode" -> {
                if ("SELECTIVE".equals(fallback) || "FULL".equals(fallback)) yield List.of("SELECTIVE", "FULL");
                if (List.of("lite", "heavy", "condy", "polymorphic").contains(fallback)) {
                    yield List.of("lite", "heavy", "condy", "polymorphic");
                }
                yield List.of("safe", "aggressive");
            }
            case "strength" -> List.of("LIGHT", "GOOD", "AGGRESSIVE");
            case "format" -> List.of("json", "html");
            case "algorithm" -> List.of("AES/GCM/NoPadding", "AES/CTR/NoPadding");
            case "failure-action" -> List.of("throw", "exit", "halt", "warn");
            case "coverage" -> List.of("entrypoints", "all-methods", "all-classes", "selected");
            case "optimization-level" -> List.of("O0", "O1", "O2", "O3");
            default -> fallback != null && fallback.contains(",") ? List.of() : List.of();
        };
    }

    private static Range range(String key, int initial) {
        if (key.contains("probability") || key.endsWith("-rate") || key.equals("copies")) return new Range(0, 100, 1);
        if (key.contains("threshold-ms")) return new Range(10, 5000, 10);
        if (key.contains("iterations")) return new Range(1000, 10_000_000, 1000);
        if (key.contains("instructions")) return new Range(100, Math.max(24_000, initial * 2), 100);
        if (key.contains("count") || key.contains("classes") || key.contains("methods") || key.contains("fields")
                || key.contains("depth") || key.contains("fragments")) {
            return new Range(0, Math.max(512, initial * 4), 1);
        }
        return new Range(0, Math.max(1000, Math.abs(initial) * 4), 1);
    }

    private static String unit(String key) {
        if (key.contains("probability") || key.endsWith("-rate")) return "%";
        if (key.endsWith("-ms")) return "ms";
        return "";
    }

    private static String label(String key) {
        StringBuilder label = new StringBuilder();
        for (String part : key.split("-")) {
            if (!label.isEmpty()) label.append(' ');
            label.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return label.toString();
    }

    private static String description(String key) {
        if (key.equals("remove-kotlin-metadata")) {
            return "Removes Kotlin reflection metadata. Leave disabled for Kotlin runtime compatibility.";
        }
        if (key.contains("probability")) return "Percentage of eligible locations where this behavior is applied.";
        if (key.contains("seed")) return "Optional deterministic seed. Zero uses the project seed or a generated value.";
        if (key.startsWith("max-")) return "Upper safety bound that limits output growth and processing cost.";
        if (key.startsWith("min-")) return "Minimum eligibility threshold before the transformation is applied.";
        if (key.contains("instructions")) return "Bytecode size guard used to avoid oversized methods.";
        return "Controls the transformer’s " + key.replace('-', ' ') + " behavior.";
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private record Range(int min, int max, int step) {}
}
