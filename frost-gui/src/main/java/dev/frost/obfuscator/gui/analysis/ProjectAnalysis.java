package dev.frost.obfuscator.gui.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ProjectAnalysis(
        Path jar,
        long sizeBytes,
        int classCount,
        int resourceCount,
        int methodComplexityScore,
        int javaVersion,
        String mainClass,
        List<String> packageRoots,
        List<String> frameworks,
        boolean fatJar,
        Map<String, String> manifest,
        boolean reflectionUsage,
        boolean serviceLoaders,
        boolean nativeLibraries,
        boolean signed,
        List<String> dependencies,
        List<String> resolvedLibraries,
        List<String> unresolvedLibraries,
        List<String> keepRules,
        List<String> exclusions,
        String suggestedOutput,
        String suggestedPackage,
        String suggestedDictionary,
        BytecodeInventory inventory
) {
    public static ProjectAnalysis empty() {
        return new ProjectAnalysis(null, 0, 0, 0, 0, 0, "", List.of(), List.of(), false,
                Map.of(), false, false, false, false, List.of(), List.of(), List.of(),
                List.of(), List.of(), "", "obf", "alphabet", BytecodeInventory.empty());
    }

    public boolean analyzed() {
        return jar != null;
    }
}
