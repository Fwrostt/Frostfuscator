package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.List;

public final class GeneratedMemberGraphBuilder implements GraphBuilder<List<GeneratedMember>> {
    @Override public Graph build(List<GeneratedMember> source, GraphBuildContext context) {
        GraphCollector out = new GraphCollector(GraphIds.graphId(GraphType.GENERATED_MEMBER, String.valueOf(source)),
                "Generated members", GraphType.GENERATED_MEMBER, context.options());
        if (source != null) for (GeneratedMember member : source) {
            String transformer = GraphIds.nodeId("transformer", member.transformerId());
            String generated = GraphIds.nodeId("generated", member.owner() + "." + member.name() + member.descriptor());
            out.addNode(new GraphNode(transformer, member.transformerId(), NodeType.TRANSFORMER, GraphMetadata.EMPTY));
            out.addNode(new GraphNode(generated, member.owner() + "." + member.name(), NodeType.GENERATED_MEMBER,
                    GraphMetadata.builder().put("owner", member.owner()).put("name", member.name())
                            .put("descriptor", member.descriptor()).putAll(member.metadata().values()).build()));
            out.addEdge(new GraphEdge(null, transformer, generated, EdgeType.GENERATED_BY, "", GraphMetadata.EMPTY));
        }
        return out.build();
    }
}
