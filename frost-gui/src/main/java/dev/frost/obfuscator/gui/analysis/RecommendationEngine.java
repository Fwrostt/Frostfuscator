package dev.frost.obfuscator.gui.analysis;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.transformer.TransformerConfig;

import java.util.ArrayList;
import java.util.List;

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
        if (analysis.reflectionUsage()) {
            recommendations.add(new Recommendation("reflection-keep", "Preserve reflection-sensitive types",
                    "Reflection strings and method handles were detected. Broad renaming can break runtime lookup.",
                    "Add suggested keep rules", 95));
        }
        if (!analysis.frameworks().isEmpty()) {
            recommendations.add(new Recommendation("framework-keep", "Apply framework compatibility rules",
                    String.join(", ", analysis.frameworks()) + " components often rely on names, annotations, or generated metadata.",
                    "Apply framework rules", 90));
        }
        if (analysis.serviceLoaders()) {
            recommendations.add(new Recommendation("services", "Keep service provider entrypoints",
                    "META-INF/services entries are name-sensitive and should remain synchronized with renamed classes.",
                    "Keep providers", 85));
        }
        if (analysis.signed()) {
            recommendations.add(new Recommendation("signing", "Plan to re-sign the output",
                    "The input contains signing metadata. Any bytecode change invalidates the original signature.",
                    "Exclude signature files", 80));
        }
        if (!analysis.unresolvedLibraries().isEmpty()) {
            recommendations.add(new Recommendation("libraries", "Resolve missing libraries",
                    analysis.unresolvedLibraries().size() + " declared dependencies could not be located automatically.",
                    "Review dependencies", 98));
        }
        if (analysis.classCount() > 10_000 && "Maximum".equalsIgnoreCase(profile)) {
            recommendations.add(new Recommendation("large-maximum", "Reduce the Maximum profile for this archive",
                    "A very large class graph with Maximum protection may produce long builds and substantial growth.",
                    "Use Strong profile", 70));
        }
        if (overheadPreference < 0.25 && enabled(config, "virtualization")) {
            recommendations.add(new Recommendation("runtime-budget", "Disable virtualization for lower overhead",
                    "Virtualization is powerful but conflicts with the selected low-runtime-overhead preference.",
                    "Disable virtualization", 75));
        }
        double estimatedMb = estimatedOutputBytes(analysis, config) / 1024d / 1024d;
        if (outputLimitMb > 0 && estimatedMb > outputLimitMb) {
            recommendations.add(new Recommendation("size-limit", "Estimated output exceeds the size limit",
                    "Current settings estimate " + String.format("%.1f MB", estimatedMb)
                            + " against a " + String.format("%.1f MB", outputLimitMb) + " limit.",
                    "Optimize for size", 92));
        }
        return recommendations.stream().sorted((a, b) -> Integer.compare(b.priority(), a.priority())).toList();
    }

    public Impact estimate(ProjectAnalysis analysis, ObfuscationConfig config) {
        long enabled = config.getTransformers().values().stream().filter(TransformerConfig::isEnabled).count();
        double growth = 4 + enabled * 0.9 + (enabled(config, "fake-classes") ? 12 : 0)
                + (enabled(config, "virtualization") ? 18 : 0);
        double overhead = enabled * 0.12 + (enabled(config, "flow-obfuscation") ? 2.2 : 0)
                + (enabled(config, "virtualization") ? 8 : 0);
        long seconds = Math.max(2, analysis.classCount() / 70L + enabled * 2);
        return new Impact(Math.round(growth), Math.round(overhead * 10) / 10d, seconds);
    }

    public long estimatedOutputBytes(ProjectAnalysis analysis, ObfuscationConfig config) {
        return Math.round(analysis.sizeBytes() * (1 + estimate(analysis, config).outputGrowthPercent() / 100d));
    }

    private static boolean enabled(ObfuscationConfig config, String transformer) {
        TransformerConfig value = config.getTransformerConfig(transformer);
        return value != null && value.isEnabled();
    }

    public record Impact(long outputGrowthPercent, double runtimeOverheadPercent, long buildSeconds) {}
}
