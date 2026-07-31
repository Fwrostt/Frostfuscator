package dev.frost.obfuscator.gui.analysis;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.transformer.TransformerConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BuildAnalytics(
        ProjectAnalysis input,
        ProjectAnalysis output,
        Duration duration,
        Map<String, Long> counters,
        double sizeGrowthPercent,
        double stringProtectionPercent,
        double methodProtectionPercent,
        double classRenamePercent,
        List<Metric> metrics,
        List<String> optimizations
) {
    public static BuildAnalytics empty() {
        return new BuildAnalytics(ProjectAnalysis.empty(), ProjectAnalysis.empty(), Duration.ZERO,
                Map.of(), 0, 0, 0, 0, List.of(), List.of());
    }

    public static BuildAnalytics compare(ProjectAnalysis input, ProjectAnalysis output,
                                         Duration duration, Map<String, Long> rawCounters,
                                         ObfuscationConfig config) {
        Map<String, Long> counters = Map.copyOf(new LinkedHashMap<>(rawCounters));
        long encryptedStrings = counters.getOrDefault("encryptedStrings",
                Math.max(0, input.inventory().stringLiteralCount()
                        - output.inventory().stringLiteralCount()));
        long protectedMethods = counters.getOrDefault("outlinedMethods", 0L)
                + counters.getOrDefault("flattenedMethods", 0L)
                + counters.getOrDefault("partiallyFlattenedMethods", 0L)
                + counters.getOrDefault("virtualizedMethods", 0L)
                + counters.getOrDefault("nativeMethodsConverted", 0L);
        double growth = percentDelta(input.sizeBytes(), output.sizeBytes());
        double stringCoverage = percent(encryptedStrings, input.inventory().stringLiteralCount());
        double methodCoverage = percent(protectedMethods,
                Math.max(1, input.inventory().methodCount() - input.inventory().abstractMethodCount()
                        - input.inventory().nativeMethodCount()));
        double renameCoverage = percent(counters.getOrDefault("classMappings", 0L), input.classCount());

        List<Metric> metrics = new ArrayList<>();
        metrics.add(new Metric("File size", signedPercent(growth),
                formatBytes(input.sizeBytes()) + " → " + formatBytes(output.sizeBytes()), toneForGrowth(growth)));
        metrics.add(new Metric("Strings encrypted", String.format("%,d", encryptedStrings),
                oneDecimal(stringCoverage) + "% of literal occurrences", stringCoverage > 70 ? "success" : "info"));
        metrics.add(new Metric("Methods transformed", String.format("%,d", protectedMethods),
                oneDecimal(methodCoverage) + "% of concrete methods", methodCoverage > 45 ? "success" : "info"));
        metrics.add(new Metric("Classes renamed", String.format("%,d",
                counters.getOrDefault("classMappings", 0L)),
                oneDecimal(renameCoverage) + "% of input classes", renameCoverage > 60 ? "success" : "info"));
        addMetric(metrics, counters, "outlinedMethods", "Methods outlined", "safe bodies moved behind delegates");
        addMetric(metrics, counters, "flattenedMethods", "Methods flattened", "full control-flow flattening");
        addMetric(metrics, counters, "partiallyFlattenedMethods", "Methods partially flattened", "partial control-flow coverage");
        addMetric(metrics, counters, "threadInterleavedExpressions", "Expressions thread-split",
                "pure primitive expressions executed across joined workers");
        addMetric(metrics, counters, "threadInterleavedWorkers", "Thread workers generated",
                "volatile-register workers added to the output");
        addMetric(metrics, counters, "virtualizedMethods", "Methods virtualized", "translated to FrostVM bytecode");
        addMetric(metrics, counters, "encryptedResources", "Resources encrypted", "non-class assets protected");
        addMetric(metrics, counters, "compressedResources", "Resources compressed", "assets compressed");
        addMetric(metrics, counters, "totalMappings", "Names remapped", "class, method, and field mappings");
        addMetric(metrics, counters, "junkCodeMembers", "Junk members added", "structural noise injected");
        addMetric(metrics, counters, "deadMethodsRemoved", "Dead methods removed", "unreachable methods deleted");
        addMetric(metrics, counters, "inlinedCalls", "Calls inlined", "eligible call sites optimized");

        List<String> optimizations = new ArrayList<>();
        if (growth > 25 && !enabled(config, "resource-compression")) {
            optimizations.add("Enable Resource Compression to offset the " + oneDecimal(growth)
                    + "% archive growth.");
        }
        if (growth > 35 && enabled(config, "fake-classes")) {
            optimizations.add("Reduce Fake Classes count; generated decoys are a major contributor to output growth.");
        }
        if (stringCoverage < 60 && enabled(config, "string-encryption")
                && input.inventory().stringLiteralCount() > 0) {
            optimizations.add("String coverage is " + oneDecimal(stringCoverage)
                    + "%. Lower the minimum length or review transformer exclusions.");
        }
        if (methodCoverage < 20 && input.inventory().outlineableMethodCount() > 0
                && !enabled(config, "flow-outliner")) {
            optimizations.add("Enable Flow Outliner for "
                    + input.inventory().outlineableMethodCount() + " pre-qualified methods.");
        }
        if (counters.getOrDefault("virtualizationSkippedUnsupported", 0L) > 0) {
            optimizations.add(counters.get("virtualizationSkippedUnsupported")
                    + " virtualization candidates used unsupported bytecode; narrow inclusions to high-value methods.");
        }
        if (optimizations.isEmpty()) {
            optimizations.add("Coverage and output growth are balanced for the selected transformer set.");
        }
        return new BuildAnalytics(input, output, duration, counters, growth, stringCoverage,
                methodCoverage, renameCoverage, List.copyOf(metrics), List.copyOf(optimizations));
    }

    public boolean available() {
        return input.analyzed() && output.analyzed();
    }

    private static void addMetric(List<Metric> metrics, Map<String, Long> counters,
                                  String key, String label, String detail) {
        long value = counters.getOrDefault(key, 0L);
        if (value > 0) metrics.add(new Metric(label, String.format("%,d", value), detail, "info"));
    }

    private static boolean enabled(ObfuscationConfig config, String name) {
        TransformerConfig transformer = config.getTransformerConfig(name);
        return transformer != null && transformer.isEnabled();
    }

    private static double percent(long part, long total) {
        return total <= 0 ? 0 : Math.min(100, part * 100d / total);
    }

    private static double percentDelta(long before, long after) {
        return before <= 0 ? 0 : (after - before) * 100d / before;
    }

    private static String signedPercent(double value) {
        return (value >= 0 ? "+" : "") + oneDecimal(value) + "%";
    }

    private static String toneForGrowth(double growth) {
        return growth <= 10 ? "success" : growth <= 30 ? "info" : "warning";
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024d;
        if (kib < 1024) return oneDecimal(kib) + " KiB";
        return oneDecimal(kib / 1024d) + " MiB";
    }

    public record Metric(String label, String value, String detail, String tone) {}
}
