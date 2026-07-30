package dev.frost.graph;

import java.util.*;

/** Immutable graph result with deterministic ordering and structured warnings. */
public record Graph(String id, String title, GraphType type, List<GraphNode> nodes,
                    List<GraphEdge> edges, GraphMetadata metadata,
                    List<GraphWarning> warnings, boolean truncated) {
    public Graph {
        id = Objects.requireNonNull(id, "id");
        title = title == null ? id : title;
        type = type == null ? GraphType.CUSTOM : type;
        nodes = sortedNodes(nodes);
        edges = sortedEdges(edges);
        metadata = metadata == null ? GraphMetadata.EMPTY : metadata;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    private static List<GraphNode> sortedNodes(List<GraphNode> source) {
        if (source == null) return List.of();
        return source.stream().sorted(Comparator.comparing(GraphNode::id)).toList();
    }

    private static List<GraphEdge> sortedEdges(List<GraphEdge> source) {
        if (source == null) return List.of();
        return source.stream().sorted(Comparator.comparing(GraphEdge::id)).toList();
    }

    public Optional<GraphNode> node(String nodeId) {
        return nodes.stream().filter(node -> node.id().equals(nodeId)).findFirst();
    }
}
