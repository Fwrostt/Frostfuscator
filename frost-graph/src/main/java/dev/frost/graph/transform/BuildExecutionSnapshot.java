package dev.frost.graph.transform;

import dev.frost.graph.GraphMetadata;
import java.util.*;

/** Compact build graph source detached from core engine state. */
public record BuildExecutionSnapshot(String buildId, List<TransformerDescriptor> plan,
                                     List<TransformerResult> results, GraphMetadata verification,
                                     GraphMetadata summary) {
    public BuildExecutionSnapshot {
        buildId = buildId == null ? "build" : buildId;
        plan = plan == null ? List.of() : List.copyOf(plan);
        results = results == null ? List.of() : List.copyOf(results);
        verification = verification == null ? GraphMetadata.EMPTY : verification;
        summary = summary == null ? GraphMetadata.EMPTY : summary;
    }
}
