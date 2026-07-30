package dev.frost.graph;

import java.util.Objects;

/** Immutable directed neutral graph edge. */
public record GraphEdge(String id, String source, String target, EdgeType type,
                        String label, GraphMetadata metadata) {
    public GraphEdge {
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        type = type == null ? EdgeType.CUSTOM : type;
        label = label == null ? "" : label;
        metadata = metadata == null ? GraphMetadata.EMPTY : metadata;
        id = id == null || id.isBlank()
                ? GraphIds.edgeId(source, target, type, label)
                : id;
    }
}
