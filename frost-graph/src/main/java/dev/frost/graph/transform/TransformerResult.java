package dev.frost.graph.transform;

import dev.frost.graph.GraphMetadata;
import java.util.*;

/** Actual, post-execution transformer measurements; never retains bytecode objects. */
public record TransformerResult(TransformerDescriptor transformer, long inspected, long modified,
                                long generated, long durationMillis, List<String> warnings,
                                String failure, GraphMetadata statistics) {
    public TransformerResult {
        transformer = Objects.requireNonNull(transformer, "transformer");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        statistics = statistics == null ? GraphMetadata.EMPTY : statistics;
    }
    public boolean successful() { return failure == null || failure.isBlank(); }
}
