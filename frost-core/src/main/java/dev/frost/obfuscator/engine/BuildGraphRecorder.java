package dev.frost.obfuscator.engine;

import dev.frost.graph.GraphMetadata;
import dev.frost.graph.transform.*;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.*;
import org.objectweb.asm.tree.ClassNode;

import java.time.Instant;
import java.util.*;

/** Records only compact scalar snapshots; ASM nodes and pools never escape a transformer call. */
final class BuildGraphRecorder {
    private final String buildId = Instant.now().toString();
    private final List<TransformerDescriptor> plan;
    private final List<TransformerResult> results = new ArrayList<>();

    BuildGraphRecorder(List<Transformer> transformers, ObfuscationConfig config) {
        List<TransformerDescriptor> descriptors = new ArrayList<>();
        int index = 0;
        for (Transformer transformer : transformers) {
            TransformerConfig item = config.getTransformerConfig(transformer.getName());
            TransformerDescriptor descriptor = descriptor(transformer, item, true);
            descriptors.add(new TransformerDescriptor(descriptor.id(), descriptor.displayName(), descriptor.enabled(),
                    transformer.priority().ordinal() * 100_000 + index++, descriptor.phase(), descriptor.dependencies(),
                    descriptor.conflicts(), descriptor.inclusions(), descriptor.exclusions(), descriptor.configuration()));
        }
        plan = List.copyOf(descriptors);
    }

    TransformerDescriptor descriptor(Transformer transformer, TransformerConfig config, boolean enabled) {
        TransformerConfig value = config == null ? new TransformerConfig() : config;
        return new TransformerDescriptor(transformer.graphId(), transformer.getName(), enabled,
                transformer.priority().ordinal(), transformer.priority().name(), transformer.dependencies(),
                transformer.conflicts(), value.getInclusions(), value.getExclusions(),
                GraphMetadata.builder().put("category", transformer.getCategory()).put("dictionary", value.getDictionary())
                        .put("options", new TreeMap<>(value.getOptions())).build());
    }

    Measurement begin(ClassPool pool, MappingCollector mappings, ProtectionStats stats) {
        return new Measurement(pool.transformableSize(), pool.size(), memberCount(pool), pool.dirtyClassCount(),
                pool.generatedClassCount(), mappings.totalMappings(), stats.counters());
    }

    void complete(Transformer transformer, TransformerConfig config, Measurement before, ClassPool pool,
                  MappingCollector mappings, ProtectionStats stats, long durationMillis, Throwable failure) {
        long members = memberCount(pool);
        long generated = Math.max(0, pool.size() - before.classes())
                + Math.max(0, members - before.members())
                + Math.max(0, pool.generatedClassCount() - before.generatedClasses());
        long modified = Math.max(0, pool.dirtyClassCount() - before.dirtyClasses())
                + Math.max(0, mappings.totalMappings() - before.mappings());
        Map<String, Long> deltas = new TreeMap<>();
        stats.counters().forEach((key, value) -> {
            long delta = value - before.statistics().getOrDefault(key, 0L);
            if (delta != 0) deltas.put(key, delta);
        });
        List<String> warnings = deltas.entrySet().stream()
                .filter(entry -> entry.getValue() > 0 && (entry.getKey().toLowerCase(Locale.ROOT).contains("warning")
                        || entry.getKey().toLowerCase(Locale.ROOT).contains("failure")
                        || entry.getKey().toLowerCase(Locale.ROOT).contains("error")))
                .map(entry -> entry.getKey() + ": " + entry.getValue()).toList();
        results.add(new TransformerResult(descriptor(transformer, config, true), before.transformableClasses(),
                modified, generated, durationMillis, warnings, failure == null ? null : summarize(failure),
                GraphMetadata.builder().put("counterDeltas", deltas).put("membersBefore", before.members())
                        .put("membersAfter", members).build()));
    }

    BuildExecutionSnapshot snapshot(Map<String, Long> stats, boolean outputWritten) {
        return new BuildExecutionSnapshot(buildId, plan, results,
                GraphMetadata.builder().put("outputWritten", outputWritten)
                        .put("successful", outputWritten && results.stream().allMatch(TransformerResult::successful)).build(),
                GraphMetadata.builder().put("statistics", new TreeMap<>(stats)).put("startedAt", buildId).build());
    }

    private static long memberCount(ClassPool pool) {
        long count = 0;
        for (ClassNode node : pool.getClassMap().values()) count += node.fields.size() + node.methods.size();
        return count;
    }
    private static String summarize(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
    record Measurement(long transformableClasses, long classes, long members, long dirtyClasses,
                       long generatedClasses, long mappings, Map<String, Long> statistics) {
        Measurement { statistics = Map.copyOf(statistics); }
    }
}
