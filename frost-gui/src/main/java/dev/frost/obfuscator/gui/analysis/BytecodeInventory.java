package dev.frost.obfuscator.gui.analysis;

import java.util.List;
import java.util.Map;

/**
 * Complete structural inventory collected directly from JVM class files.
 * Lists are intentionally retained so the GUI can search every discovered
 * class, method, and string rather than presenting sampled estimates.
 */
public record BytecodeInventory(
        long fieldCount,
        long methodCount,
        long constructorCount,
        long instructionCount,
        long stringLiteralCount,
        long uniqueStringCount,
        long stringCharacterCount,
        long numericConstantCount,
        long branchCount,
        long switchCount,
        long tryCatchCount,
        long methodCallCount,
        long fieldAccessCount,
        long invokeDynamicCount,
        long annotationCount,
        long nativeMethodCount,
        long abstractMethodCount,
        long synchronizedMethodCount,
        long syntheticMethodCount,
        long bridgeMethodCount,
        long publicMethodCount,
        long staticMethodCount,
        long virtualizableMethodCount,
        long outlineableMethodCount,
        long lineNumberCount,
        long localVariableCount,
        long interfaceCount,
        long abstractClassCount,
        long enumCount,
        long recordCount,
        long annotationClassCount,
        long syntheticClassCount,
        long innerClassCount,
        List<ClassInsight> classes,
        List<MethodInsight> methods,
        List<StringInsight> strings,
        List<ResourceEntry> resourceEntries,
        Map<String, ResourceInsight> resources,
        List<CompatibilitySignal> compatibilitySignals
) {
    public static BytecodeInventory empty() {
        return new BytecodeInventory(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }

    public record ClassInsight(
            String name,
            String packageName,
            String kind,
            int fields,
            int methods,
            int instructions,
            int strings,
            int annotations,
            String flags
    ) {}

    public record MethodInsight(
            String owner,
            String name,
            String descriptor,
            int instructions,
            int complexity,
            int strings,
            int calls,
            int tryCatchBlocks,
            String flags
    ) {
        public String qualifiedName() {
            return owner + "." + name + descriptor;
        }
    }

    public record StringInsight(
            String value,
            int occurrences,
            int characters,
            List<String> locations,
            String category
    ) {}

    public record ResourceInsight(long files, long bytes) {}

    public record ResourceEntry(String name, String type, long bytes) {}

    public record CompatibilitySignal(
            String id,
            String severity,
            String title,
            String evidence,
            List<String> affectedTransformers,
            List<String> suggestedRules
    ) {}
}
