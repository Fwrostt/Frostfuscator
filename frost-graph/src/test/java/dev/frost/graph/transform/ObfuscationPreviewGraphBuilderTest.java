package dev.frost.graph.transform;

import dev.frost.graph.EdgeType;
import dev.frost.graph.Graph;
import dev.frost.graph.GraphBuildContext;
import dev.frost.graph.GraphMetadata;
import dev.frost.graph.bytecode.BytecodeClassInfo;
import dev.frost.graph.bytecode.BytecodeMethodInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObfuscationPreviewGraphBuilderTest {
    @Test void dryRunShowsApplicableTransformersWithoutMutatingTheClass() {
        BytecodeClassInfo target = new BytecodeClassInfo("app/Main", "Main", "app", false,
                List.of(new BytecodeMethodInfo("app/Main", "run", "()V", 1)));
        TransformerDescriptor included = descriptor("rename", List.of("app\\..*"), List.of());
        TransformerDescriptor excluded = descriptor("flow", List.of(), List.of("app\\.Main"));
        Graph graph = new ObfuscationPreviewGraphBuilder().build(
                new ObfuscationPreviewRequest(target, List.of(included, excluded), List.of(), List.of()),
                GraphBuildContext.defaults());
        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
        assertEquals(EdgeType.MODIFIES, graph.edges().getFirst().type());
        assertEquals(false, graph.metadata().get("mutated"));
        assertEquals(1, graph.metadata().get("excludedTransformers"));
    }

    private static TransformerDescriptor descriptor(String id, List<String> inclusions, List<String> exclusions) {
        return new TransformerDescriptor(id, id, true, 1, "NORMAL", Set.of(), Set.of(), inclusions, exclusions,
                GraphMetadata.EMPTY);
    }
}
