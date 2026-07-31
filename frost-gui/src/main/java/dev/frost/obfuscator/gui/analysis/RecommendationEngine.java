package dev.frost.obfuscator.gui.analysis;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.state.ProjectState;
import dev.frost.obfuscator.transformer.TransformerConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RecommendationEngine {

    public List<Recommendation> recommend(ProjectAnalysis analysis, ObfuscationConfig config,
                                          String profile, double outputLimitMb, double overheadPreference) {
        List<Recommendation> recommendations = new ArrayList<>();
        if (!analysis.analyzed()) {
            recommendations.add(new Recommendation("select-input", "Select and analyze an input JAR",
                    "Project-aware defaults become available after Frostfuscator inspects the archive.",
                    "Choose input JAR", 100));
            return recommendations;
        }

        BytecodeInventory inventory = analysis.inventory();
        if (analysis.reflectionUsage() && missingRules(config, analysis.exclusions())) {
            recommendations.add(action("reflection-keep", "Preserve reflection-sensitive types",
                    "Reflective call sites and class-name constants were detected. Apply the exact and framework-derived rules before renaming or dead-code removal.",
                    "Add keep rules", 100, "Compatibility",
                    List.of("class-rename", "method-rename", "field-rename", "dead-code-elimination"),
                    state -> addExclusions(state, state.analysis().exclusions())));
        }
        if (!analysis.frameworks().isEmpty() && missingRules(config, analysis.exclusions())) {
            recommendations.add(action("framework-keep", "Apply framework compatibility rules",
                    String.join(", ", analysis.frameworks())
                            + " components can depend on names, annotations, constructors, and generated metadata.",
                    "Apply framework rules", 96, "Compatibility",
                    List.of("class-rename", "method-rename", "field-rename", "metadata-noise"),
                    state -> addExclusions(state, state.analysis().exclusions())));
        }
        if (analysis.serviceLoaders() && missingRules(config, analysis.keepRules())) {
            recommendations.add(action("services", "Keep service providers",
                    "Provider class names declared under META-INF/services must remain loadable by name.",
                    "Keep providers", 98, "Compatibility", List.of("class-rename", "jar-shrinker"),
                    state -> addExclusions(state, state.analysis().keepRules())));
        }
        if (analysis.signed()) {
            recommendations.add(new Recommendation("signing", "Re-sign the protected output",
                    "The input contains signing metadata. Every bytecode or resource mutation invalidates the original signature.",
                    "", 92, "Compatibility", List.of("all mutating transformers"), null));
        }
        if (!analysis.unresolvedLibraries().isEmpty()) {
            recommendations.add(new Recommendation("libraries", "Resolve missing libraries",
                    analysis.unresolvedLibraries().size()
                            + " declared dependencies could not be located; hierarchy-aware passes may make unsafe decisions.",
                    "Review dependencies", 99, "Dependencies", List.of(), null));
        }

        if (inventory.stringLiteralCount() > 0 && !enabled(config, "string-encryption")) {
            String mode = inventory.stringLiteralCount() > 2_000 ? "medium" : "heavy";
            recommendations.add(action("enable-string-encryption", "Encrypt string literals",
                    String.format("%,d literal occurrence(s), %,d unique values, and %,d characters are currently visible in the constant pool.",
                            inventory.stringLiteralCount(), inventory.uniqueStringCount(),
                            inventory.stringCharacterCount()),
                    "Enable string encryption", 88, "Protection", List.of("string-encryption"),
                    state -> {
                        TransformerConfig transformer = transformer(state, "string-encryption");
                        transformer.setEnabled(true);
                        transformer.getOptions().put("mode", mode);
                        transformer.getOptions().putIfAbsent("min-length", 3);
                        state.touch();
                    }));
        }
        if (inventory.numericConstantCount() >= 40 && !enabled(config, "number-obfuscation")) {
            recommendations.add(enable("enable-number-obfuscation", "Obfuscate numeric constants",
                    String.format("%,d numeric constants are eligible for arithmetic encoding.",
                            inventory.numericConstantCount()),
                    68, "number-obfuscation"));
        }
        if (inventory.outlineableMethodCount() >= 8 && !enabled(config, "flow-outliner")) {
            recommendations.add(action("enable-flow-outliner", "Outline eligible method bodies",
                    String.format("%,d static method(s) meet the outliner safety requirements.",
                            inventory.outlineableMethodCount()),
                    "Enable outliner", 76, "Protection", List.of("flow-outliner"),
                    state -> {
                        TransformerConfig transformer = transformer(state, "flow-outliner");
                        transformer.setEnabled(true);
                        transformer.getOptions().putIfAbsent("probability", 30);
                        transformer.getOptions().putIfAbsent("max-per-class", 12);
                        state.touch();
                    }));
        }
        if (inventory.virtualizableMethodCount() >= 20 && overheadPreference >= 0.45
                && !analysis.nativeLibraries() && !enabled(config, "virtualization")) {
            recommendations.add(action("enable-virtualization", "Virtualize selected high-value methods",
                    String.format("%,d method(s) pass the initial bytecode eligibility scan. Start with selective coverage to control runtime cost.",
                            inventory.virtualizableMethodCount()),
                    "Enable selective virtualization", 64, "Advanced protection",
                    List.of("virtualization"), state -> {
                        TransformerConfig transformer = transformer(state, "virtualization");
                        transformer.setEnabled(true);
                        transformer.getOptions().putIfAbsent("mode", "selective");
                        state.touch();
                    }));
        }
        if ((inventory.lineNumberCount() > 0 || inventory.localVariableCount() > 0)
                && !enabled(config, "remove-debug")) {
            recommendations.add(enable("remove-debug-metadata", "Remove debug metadata",
                    String.format("%,d line entries and %,d local-variable records disclose source structure.",
                            inventory.lineNumberCount(), inventory.localVariableCount()),
                    72, "remove-debug"));
        }
        if (inventory.instructionCount() > 2_000 && !enabled(config, "bytecode-optimizer")) {
            recommendations.add(enable("enable-bytecode-optimizer", "Run bytecode cleanup",
                    String.format("%,d instructions provide useful cleanup and normalization opportunities.",
                            inventory.instructionCount()),
                    54, "bytecode-optimizer"));
        }
        if (analysis.resourceCount() >= 12 && !enabled(config, "resource-compression")) {
            recommendations.add(enable("enable-resource-compression", "Compress packaged resources",
                    analysis.resourceCount() + " non-class entries were detected. Compression can offset protection growth.",
                    52, "resource-compression"));
        }
        if (!analysis.reflectionUsage() && analysis.frameworks().isEmpty()
                && !enabled(config, "class-rename")) {
            recommendations.add(action("enable-renaming", "Enable structural renaming",
                    "No framework or reflection evidence currently blocks class, method, and field renaming.",
                    "Enable safe renaming", 82, "Protection",
                    List.of("class-rename", "method-rename", "field-rename"), state -> {
                        setEnabled(state.configuration(), "class-rename", true);
                        setEnabled(state.configuration(), "method-rename", true);
                        setEnabled(state.configuration(), "field-rename", true);
                        state.touch();
                    }));
        }
        if (analysis.classCount() > 10_000 && "Maximum".equalsIgnoreCase(profile)) {
            recommendations.add(action("large-maximum", "Use Strong for this very large archive",
                    "Maximum protection across a large class graph can produce disproportionate build time and growth.",
                    "Use Strong profile", 66, "Performance", List.of(), state -> {
                        state.profileProperty().set("Strong");
                        state.touch();
                    }));
        }
        if (overheadPreference < 0.25 && enabled(config, "virtualization")) {
            recommendations.add(action("runtime-budget", "Disable virtualization for the runtime budget",
                    "Virtualization conflicts with the selected low-runtime-overhead preference.",
                    "Disable virtualization", 84, "Performance", List.of("virtualization"),
                    state -> {
                        setEnabled(state.configuration(), "virtualization", false);
                        state.touch();
                    }));
        }
        double estimatedMb = estimatedOutputBytes(analysis, config) / 1024d / 1024d;
        if (outputLimitMb > 0 && estimatedMb > outputLimitMb) {
            recommendations.add(new Recommendation("size-limit", "Estimated output exceeds the size limit",
                    "Current settings estimate " + String.format("%.1f MB", estimatedMb)
                            + " against a " + String.format("%.1f MB", outputLimitMb) + " limit.",
                    "", 90, "Performance", List.of(), null));
        }
        return recommendations.stream().sorted((a, b) -> Integer.compare(b.priority(), a.priority())).toList();
    }

    public int applyRecommendedSetup(ProjectState state) {
        List<Recommendation> recommendations = recommend(state.analysis(), state.configuration(),
                state.profileProperty().get(), state.outputSizeLimitMbProperty().get(),
                state.runtimeOverheadPreferenceProperty().get());
        int applied = 0;
        for (Recommendation recommendation : recommendations) {
            if (!recommendation.actionable() || recommendation.id().equals("runtime-budget")
                    || recommendation.id().equals("large-maximum")) continue;
            recommendation.action().accept(state);
            applied++;
        }
        return applied;
    }

    public List<String> incompatibilities(ProjectAnalysis analysis, ObfuscationConfig config) {
        Set<String> issues = new LinkedHashSet<>();
        if (enabled(config, "classloader-encryption") && enabled(config, "integrity")) {
            issues.add("Encrypted ClassLoader conflicts with Integrity Index.");
        }
        if (config.getFrostJNI() != null && config.getFrostJNI().isEnabled()
                && enabled(config, "classloader-encryption")) {
            issues.add("FrostJNI conflicts with Encrypted ClassLoader.");
        }
        if (analysis.nativeLibraries() && enabled(config, "classloader-encryption")) {
            issues.add("Encrypted ClassLoader can break native resource extraction.");
        }
        if (analysis.reflectionUsage() && renamingEnabled(config)
                && missingRules(config, analysis.exclusions())) {
            issues.add("Renaming is unsafe until reflection keep rules are applied.");
        }
        if (!analysis.frameworks().isEmpty() && enabled(config, "dead-code-elimination")
                && missingRules(config, analysis.exclusions())) {
            issues.add("Dead-code elimination is unsafe for framework-created types without keep rules.");
        }
        return List.copyOf(issues);
    }

    public Impact estimate(ProjectAnalysis analysis, ObfuscationConfig config) {
        long enabled = config.getTransformers().values().stream().filter(TransformerConfig::isEnabled).count();
        double growth = 4 + enabled * 0.9 + (enabled(config, "fake-classes") ? 12 : 0)
                + (enabled(config, "virtualization") ? 18 : 0);
        double overhead = enabled * 0.12 + (enabled(config, "flow-obfuscation") ? 2.2 : 0)
                + (enabled(config, "thread-interleaved-flow") ? 3.0 : 0)
                + (enabled(config, "virtualization") ? 8 : 0);
        long seconds = Math.max(2, analysis.classCount() / 70L + enabled * 2);
        return new Impact(Math.round(growth), Math.round(overhead * 10) / 10d, seconds);
    }

    public long estimatedOutputBytes(ProjectAnalysis analysis, ObfuscationConfig config) {
        return Math.round(analysis.sizeBytes() * (1 + estimate(analysis, config).outputGrowthPercent() / 100d));
    }

    private static Recommendation enable(String id, String title, String explanation,
                                         int priority, String transformer) {
        return action(id, title, explanation, "Enable " + transformer, priority, "Protection",
                List.of(transformer), state -> {
                    setEnabled(state.configuration(), transformer, true);
                    state.touch();
                });
    }

    private static Recommendation action(String id, String title, String explanation,
                                         String label, int priority, String category,
                                         List<String> transformers,
                                         java.util.function.Consumer<ProjectState> action) {
        return new Recommendation(id, title, explanation, label, priority, category,
                transformers, action);
    }

    private static void addExclusions(ProjectState state, List<String> values) {
        List<String> rules = new ArrayList<>(state.configuration().getExclusions() == null
                ? List.of() : state.configuration().getExclusions());
        for (String value : values) if (!rules.contains(value)) rules.add(value);
        state.configuration().setExclusions(rules);
        state.touch();
    }

    private static boolean missingRules(ObfuscationConfig config, List<String> rules) {
        List<String> existing = config.getExclusions() == null ? List.of() : config.getExclusions();
        return rules.stream().anyMatch(rule -> !existing.contains(rule));
    }

    private static TransformerConfig transformer(ProjectState state, String name) {
        return state.configuration().getTransformers()
                .computeIfAbsent(name, key -> new TransformerConfig());
    }

    private static boolean renamingEnabled(ObfuscationConfig config) {
        return enabled(config, "class-rename") || enabled(config, "field-rename")
                || enabled(config, "method-rename");
    }

    private static boolean enabled(ObfuscationConfig config, String transformer) {
        TransformerConfig value = config.getTransformerConfig(transformer);
        return value != null && value.isEnabled();
    }

    private static void setEnabled(ObfuscationConfig config, String name, boolean enabled) {
        config.getTransformers().computeIfAbsent(name, key -> new TransformerConfig())
                .setEnabled(enabled);
    }

    public record Impact(long outputGrowthPercent, double runtimeOverheadPercent, long buildSeconds) {}
}
