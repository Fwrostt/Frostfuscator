package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.*;

public final class MappingGraphBuilder implements GraphBuilder<Map<String, String>> {
    @Override public Graph build(Map<String, String> mappings, GraphBuildContext context) {
        GraphCollector out = new GraphCollector(GraphIds.graphId(GraphType.MAPPING, String.valueOf(mappings)),
                "Mappings", GraphType.MAPPING, context.options());
        if (mappings != null) new TreeMap<>(mappings).forEach((before, after) -> {
            String from = GraphIds.nodeId("mapping-before", before), to = GraphIds.nodeId("mapping-after", after);
            out.addNode(new GraphNode(from, before, NodeType.MAPPING, GraphMetadata.builder().put("state", "before").build()));
            out.addNode(new GraphNode(to, after, NodeType.MAPPING, GraphMetadata.builder().put("state", "after").build()));
            out.addEdge(new GraphEdge(null, from, to, EdgeType.RENAMED_TO, "", GraphMetadata.EMPTY));
        });
        return out.build();
    }
}
