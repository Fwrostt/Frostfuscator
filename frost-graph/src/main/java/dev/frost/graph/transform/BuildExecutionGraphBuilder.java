package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.*;

public final class BuildExecutionGraphBuilder implements GraphBuilder<BuildExecutionSnapshot> {
    @Override public Graph build(BuildExecutionSnapshot source, GraphBuildContext context) {
        GraphCollector out = new GraphCollector(GraphIds.graphId(GraphType.BUILD_EXECUTION, source.buildId()),
                "Build execution", GraphType.BUILD_EXECUTION, context.options());
        Map<String, TransformerResult> results = new HashMap<>();
        source.results().forEach(result -> results.put(result.transformer().id(), result));
        GraphNode previous = null;
        for (TransformerDescriptor descriptor : source.plan()) {
            TransformerResult result = results.get(descriptor.id());
            GraphMetadata.Builder metadata = GraphMetadata.builder().put("id", descriptor.id())
                    .put("phase", descriptor.phase()).put("priority", descriptor.priority())
                    .put("status", result == null ? "pending" : result.successful() ? "completed" : "failed");
            if (result != null) metadata.put("inspected", result.inspected()).put("modified", result.modified())
                    .put("generated", result.generated()).put("durationMs", result.durationMillis())
                    .put("warnings", result.warnings()).put("failure", result.failure())
                    .put("statistics", result.statistics().values());
            GraphNode node = new GraphNode(GraphIds.nodeId("transformer", descriptor.id()), descriptor.displayName(),
                    NodeType.TRANSFORMER, metadata.build());
            out.addNode(node);
            if (previous != null) out.addEdge(new GraphEdge(null, previous.id(), node.id(), EdgeType.EXECUTES_BEFORE, "", GraphMetadata.EMPTY));
            previous = node;
        }
        if (!source.verification().values().isEmpty()) {
            GraphNode verification = new GraphNode(GraphIds.nodeId("verification", source.buildId()), "Verification",
                    NodeType.VERIFICATION, source.verification());
            out.addNode(verification);
            if (previous != null) out.addEdge(new GraphEdge(null, previous.id(), verification.id(), EdgeType.VERIFIES, "", GraphMetadata.EMPTY));
        }
        out.metadata(source.summary());
        return out.build();
    }
}
