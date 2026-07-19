package dev.frost.obfuscator.config;

import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.TransformerRegistry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TransformerProfiles {
    private static final Set<String> NAMES = Set.of("none", "basic", "balanced", "strong", "maximum");

    private TransformerProfiles() {
    }

    public static Set<String> names() {
        return NAMES;
    }

    public static void apply(ObfuscationConfig config, String profile) {
        String normalized = profile == null || profile.isBlank()
                ? "balanced"
                : profile.trim().toLowerCase(Locale.ROOT);
        if (!NAMES.contains(normalized)) {
            throw new IllegalArgumentException("Unknown profile '" + profile + "'. Use one of " + NAMES);
        }

        switch (normalized) {
            case "none" -> noPasses(config);
            case "basic" -> basic(config);
            case "strong" -> strong(config);
            case "maximum" -> maximum(config);
            default -> balanced(config);
        }
    }

    public static void applySeed(ObfuscationConfig config, long seed) {
        if (seed <= 0) {
            return;
        }
        config.setSeed(seed);
        for (TransformerConfig transformerConfig : config.getTransformers().values()) {
            transformerConfig.getOptions().putIfAbsent("seed", seed);
        }
    }

    private static void noPasses(ObfuscationConfig config) {
        config.setDictionary("alphabet");
        config.setPackageMode("keep");
        config.setFlattenPackage("obf");
        for (String name : TransformerRegistry.getAllNames()) {
            set(config, name, false, config.getTransformers()
                    .getOrDefault(name, new TransformerConfig())
                    .getOptions());
        }
    }

    private static void basic(ObfuscationConfig config) {
        noPasses(config);
        set(config, "remove-debug", true, options("remove-source-file", true, "remove-line-numbers", true, "remove-local-variables", true, "remove-parameters", true));
        set(config, "string-encryption", true, options("mode", "lite", "min-length", 2, "max-method-instructions", 6000));
        set(config, "class-rename", true, options("mode", "safe"));
        set(config, "field-rename", true, options("mode", "safe"));
        set(config, "method-rename", true, options("mode", "safe"));
        set(config, "watermark", false, options("owner", "unknown", "id", "change-me", "class-annotations", true, "string-field", true, "field-name", "__frost$watermark"));
        set(config, "integrity", false, options());
        set(config, "statistics-report", false, options("format", "json", "output", "frost-report.json"));
    }

    private static void balanced(ObfuscationConfig config) {
        config.setDictionary("alphabet");
        config.setPackageMode("keep");
        config.setFlattenPackage("obf");
        set(config, "class-rename", true, options("mode", "aggressive"));
        set(config, "field-rename", true, options("mode", "aggressive"));
        set(config, "method-rename", true, options("mode", "aggressive"));
        set(config, "local-variable-rename", true, options());
        set(config, "remove-debug", true, options("remove-source-file", true, "remove-line-numbers", true, "remove-local-variables", true, "remove-parameters", true));
        set(config, "string-encryption", true, options("mode", "heavy", "min-length", 1, "max-method-instructions", 6000));
        set(config, "number-obfuscation", true, options("probability", 80, "max-per-method", 96, "max-per-class", 256, "max-method-instructions", 6000));
        set(config, "parameter-encryption", true, options("probability", 30));
        set(config, "flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 8, "max-predicates-per-method", 24, "min-method-instructions", 12, "max-method-instructions", 5000));
        set(config, "flow-outliner", true, options("probability", 25, "max-per-class", 16));
        set(config, "flow-range", true, options("probability", 35));
        set(config, "flow-condition", true, options("probability", 25, "max-per-method", 16));
        set(config, "flow-exception", true, options("strength", "GOOD"));
        set(config, "flow-switch", true, options("probability", 75));
        set(config, "stack-manipulation", true, options("probability", 8, "max-per-method", 16));
        set(config, "invoke-dynamic", true, options("probability", 35, "mutable-callsites", true));
        set(config, "reference-hiding", true, options("probability", 45, "max-per-class", 96, "max-method-instructions", 6000));
        set(config, "access-modifier", true, options("synthetic", true, "bridge-methods", false, "relax-final", false));
        set(config, "metadata-noise", true, options("strings-per-class", 8, "deprecated", true, "signatures", true));
        commonProtection(config, true);
    }

    private static void strong(ObfuscationConfig config) {
        balanced(config);
        set(config, "string-encryption", true, options("mode", "condy", "min-length", 1, "max-method-instructions", 6000));
        set(config, "number-obfuscation", true, options("probability", 90, "max-per-method", 128, "max-per-class", 320, "max-method-instructions", 6000));
        set(config, "parameter-encryption", true, options("probability", 45));
        set(config, "flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 12, "max-predicates-per-method", 32, "min-method-instructions", 12, "max-method-instructions", 5000));
        set(config, "flow-outliner", true, options("probability", 35, "max-per-class", 22));
        set(config, "flow-range", true, options("probability", 48));
        set(config, "flow-condition", true, options("probability", 38, "max-per-method", 24));
        set(config, "flow-exception", true, options("strength", "AGGRESSIVE"));
        set(config, "stack-manipulation", true, options("probability", 11, "max-per-method", 22));
        set(config, "invoke-dynamic", true, options("probability", 55, "mutable-callsites", true));
        set(config, "reference-hiding", true, options("probability", 65, "max-per-class", 144, "max-method-instructions", 6000));
        set(config, "metadata-noise", true, options("strings-per-class", 12, "deprecated", true, "signatures", true));
        set(config, "anti-debug", true, antiDebugOptions(false));
        set(config, "anti-decompiler", true, options());
        set(config, "junk-code", true, options("min-methods-per-class", 1, "max-methods-per-class", 3, "min-fields-per-class", 0, "max-fields-per-class", 2, "seed", 0));
        set(config, "bytecode-optimizer", true, options());
    }

    private static void maximum(ObfuscationConfig config) {
        strong(config);
        set(config, "string-encryption", true, options("mode", "polymorphic", "min-length", 1, "max-method-instructions", 6000));
        set(config, "number-obfuscation", true, options("probability", 100, "max-per-method", 160, "max-per-class", 512, "max-method-instructions", 6000));
        set(config, "parameter-encryption", true, options("probability", 60));
        set(config, "flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 16, "max-predicates-per-method", 40, "min-method-instructions", 12, "max-method-instructions", 5000));
        set(config, "flow-outliner", true, options("probability", 45, "max-per-class", 28));
        set(config, "flow-range", true, options("probability", 60));
        set(config, "flow-condition", true, options("probability", 50, "max-per-method", 32));
        set(config, "flow-switch", true, options("probability", 90));
        set(config, "stack-manipulation", true, options("probability", 14, "max-per-method", 28));
        set(config, "invoke-dynamic", true, options("probability", 75, "mutable-callsites", true));
        set(config, "reference-hiding", true, options("probability", 80, "max-per-class", 192, "max-method-instructions", 6000));
        set(config, "metadata-noise", true, options("strings-per-class", 18, "deprecated", true, "signatures", true));
        set(config, "anti-debug", true, antiDebugOptions(true));
        set(config, "junk-code", true, options("min-methods-per-class", 2, "max-methods-per-class", 5, "min-fields-per-class", 1, "max-fields-per-class", 3, "seed", 0));
        set(config, "fake-classes", true, fakeClassOptions(24, 10, 24, 2, 8));
        set(config, "jar-shrinker", true, options());
        set(config, "resource-compression", true, options("remove-originals", true, "output-prefix", "META-INF/frostfuscator/resources/"));
        set(config, "resource-encryption", true, options("remove-originals", false, "output-prefix", "META-INF/frostfuscator/encrypted/", "seed", 0));
    }

    private static void commonProtection(ObfuscationConfig config, boolean enabled) {
        set(config, "watermark", enabled, options("owner", "unknown", "id", "change-me", "class-annotations", true, "string-field", true, "field-name", "__frost$watermark"));
        set(config, "integrity", enabled, options());
        set(config, "anti-debug", false, antiDebugOptions(false));
        set(config, "anti-decompiler", enabled, options());
        set(config, "junk-code", enabled, options("min-methods-per-class", 1, "max-methods-per-class", 2, "min-fields-per-class", 0, "max-fields-per-class", 1, "seed", 0));
        set(config, "fake-classes", false, fakeClassOptions(12, 8, 16, 2, 4));
        set(config, "inject-banner", false, options("text", "Protected by Frostfuscator", "copies", 1));
        set(config, "emoji-hell", false, options("copies", 3));
        set(config, "copypasta-injector", false, options("copies", 3));
        set(config, "fake-application", false, options("profiles", "minecraft-plugin,networking-stack,enterprise", "classes-per-profile", 3, "min-methods-per-class", 8, "max-methods-per-class", 24, "min-fields-per-class", 3, "max-fields-per-class", 10, "seed", 0));
        set(config, "chinese-mode", false, options("package-mode", "random", "package-prefix", "\u51b0\u971c/\u6df7\u6dc6\u5668", "rename-members", true, "inject-fun", true, "large-banners", true, "quotes", true, "inject-metadata", true, "inject-strings", true, "min-fun-members", 1, "max-fun-members", 3));
        set(config, "resource-compression", false, options("remove-originals", true, "output-prefix", "META-INF/frostfuscator/resources/"));
        set(config, "resource-encryption", false, options("remove-originals", false, "output-prefix", "META-INF/frostfuscator/encrypted/", "seed", 0));
        set(config, "bytecode-optimizer", enabled, options());
        set(config, "jar-shrinker", false, options());
        set(config, "statistics-report", enabled, options("format", "json", "output", "frost-report.json"));
    }

    private static Map<String, Object> antiDebugOptions(boolean processChecks) {
        return options(
                "method-name", "__frost$antiDebug",
                "check-arguments", true,
                "check-debug-classes", true,
                "check-stack", true,
                "check-timing", true,
                "shared-helper", true,
                "timing-iterations", 1000000,
                "timing-threshold-ms", 80,
                "check-processes", processChecks
        );
    }

    private static Map<String, Object> fakeClassOptions(int count, int minMethods, int maxMethods, int minFields, int maxFields) {
        return options(
                "priority", "pre-obfuscation",
                "count", count,
                "min-methods-per-class", minMethods,
                "max-methods-per-class", maxMethods,
                "min-fields-per-class", minFields,
                "max-fields-per-class", maxFields,
                "kind-ratio", "regular:70,interface:10,enum:10,inner:10",
                "placement", "package-mode",
                "naming", "dictionary",
                "custom-pattern", "Fake{index}",
                "package", "frost/junk",
                "seed", 0
        );
    }

    private static void set(ObfuscationConfig config, String name, boolean enabled, Map<String, Object> options) {
        TransformerConfig transformerConfig = config.getTransformers().computeIfAbsent(name, key -> new TransformerConfig());
        transformerConfig.setEnabled(enabled);
        transformerConfig.setDictionary(config.getDictionary());
        transformerConfig.setOptions(new LinkedHashMap<>(options));
    }

    private static Map<String, Object> options(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }
}
