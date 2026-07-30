package dev.frost.graph.transform;

import dev.frost.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransformerGraphBuilderTest {
    @Test void reportsCyclesMissingDependenciesOrderAndConflicts() {
        TransformerDescriptor a = descriptor("a", 0, Set.of("b", "missing"), Set.of("c"));
        TransformerDescriptor b = descriptor("b", 2, Set.of("a"), Set.of());
        TransformerDescriptor c = descriptor("c", 1, Set.of(), Set.of());
        Graph graph = new TransformerPipelineGraphBuilder().build(List.of(a, b, c), GraphBuildContext.defaults());
        Set<String> codes = new HashSet<>(); graph.warnings().forEach(warning -> codes.add(warning.code()));
        assertTrue(codes.containsAll(Set.of("dependency-cycle", "missing-dependency", "dependency-order", "transformer-conflict")));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == EdgeType.REQUIRES));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == EdgeType.CONFLICTS));
    }
    private static TransformerDescriptor descriptor(String id, int priority, Set<String> dependencies, Set<String> conflicts) {
        return new TransformerDescriptor(id, id, true, priority, "NORMAL", dependencies, conflicts,
                List.of(), List.of(), GraphMetadata.EMPTY);
    }
}
