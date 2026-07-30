package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.*;

public final class TransformerDependencyGraphBuilder implements GraphBuilder<List<TransformerDescriptor>> {
    @Override public Graph build(List<TransformerDescriptor> source, GraphBuildContext context) {
        Graph pipeline = new TransformerPipelineGraphBuilder().build(source, context);
        List<GraphEdge> dependencies = pipeline.edges().stream().filter(edge -> edge.type() == EdgeType.REQUIRES
                || edge.type() == EdgeType.CONFLICTS).toList();
        return new Graph(pipeline.id() + "-dependencies", "Transformer dependencies", GraphType.TRANSFORMER_DEPENDENCY,
                pipeline.nodes(), dependencies, pipeline.metadata(), pipeline.warnings(), pipeline.truncated());
    }
}
