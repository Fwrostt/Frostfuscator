package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.*;

public final class TransformerResultGraphBuilder implements GraphBuilder<List<TransformerResult>> {
    @Override public Graph build(List<TransformerResult> source, GraphBuildContext context) {
        GraphCollector out = new GraphCollector(GraphIds.graphId(GraphType.TRANSFORMER_RESULT, String.valueOf(source)),
                "Transformer results", GraphType.TRANSFORMER_RESULT, context.options());
        if (source != null) for (TransformerResult result : source) {
            TransformerDescriptor item = result.transformer();
            out.addNode(new GraphNode(GraphIds.nodeId("transformer-result", item.id()), item.displayName(), NodeType.TRANSFORMER,
                    GraphMetadata.builder().put("id", item.id()).put("phase", item.phase())
                            .put("inspected", result.inspected()).put("modified", result.modified())
                            .put("generated", result.generated()).put("durationMs", result.durationMillis())
                            .put("warnings", result.warnings()).put("failure", result.failure())
                            .put("successful", result.successful()).put("statistics", result.statistics().values()).build()));
        }
        return out.build();
    }
}
