package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.*;

public final class TransformationDiffGraphBuilder implements GraphBuilder<TransformationDiff> {
    @Override public Graph build(TransformationDiff source, GraphBuildContext context) {
        GraphCollector out = new GraphCollector(GraphIds.graphId(GraphType.TRANSFORMATION_DIFF, source.toString()),
                "Transformation diff", GraphType.TRANSFORMATION_DIFF, context.options());
        Set<String> all = new TreeSet<>(source.before()); all.addAll(source.after());
        for (String name : all) {
            boolean before = source.before().contains(name), after = source.after().contains(name);
            String oldId = GraphIds.nodeId("before", name), newId = GraphIds.nodeId("after", name);
            if (before) out.addNode(new GraphNode(oldId, name, NodeType.CLASS, GraphMetadata.builder().put("state", "before").build()));
            if (after) out.addNode(new GraphNode(newId, name, before ? NodeType.CLASS : NodeType.GENERATED_MEMBER,
                    GraphMetadata.builder().put("state", "after").put("generated", !before).build()));
            if (before && after) out.addEdge(new GraphEdge(null, oldId, newId, EdgeType.BEFORE_AFTER, "", GraphMetadata.EMPTY));
            else if (after) {
                String transformer = GraphIds.nodeId("transformer", source.transformerId());
                out.addNode(new GraphNode(transformer, source.transformerId(), NodeType.TRANSFORMER, GraphMetadata.EMPTY));
                out.addEdge(new GraphEdge(null, transformer, newId, EdgeType.GENERATED_BY, "", GraphMetadata.EMPTY));
            }
        }
        return out.build();
    }
}
