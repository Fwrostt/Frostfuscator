package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.List;

public final class IncludedExcludedGraphBuilder implements GraphBuilder<List<InclusionDecision>> {
    @Override public Graph build(List<InclusionDecision> source, GraphBuildContext context) {
        GraphCollector out = new GraphCollector(GraphIds.graphId(GraphType.INCLUDED_EXCLUDED, String.valueOf(source)),
                "Included and excluded", GraphType.INCLUDED_EXCLUDED, context.options());
        if (source != null) for (InclusionDecision decision : source) out.addNode(new GraphNode(
                GraphIds.nodeId(decision.included() ? "included" : "excluded", decision.item()), decision.item(),
                decision.included() ? NodeType.CLASS : NodeType.WARNING,
                GraphMetadata.builder().put("included", decision.included()).put("reason", decision.reason()).build()));
        return out.build();
    }
}
