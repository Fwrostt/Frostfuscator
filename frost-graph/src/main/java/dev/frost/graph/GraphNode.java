package dev.frost.graph;

import java.util.Objects;

/** Immutable neutral graph node. */
public record GraphNode(String id, String label, NodeType type, GraphMetadata metadata) {
    public GraphNode {
        id = Objects.requireNonNull(id, "id");
        label = label == null ? id : label;
        type = type == null ? NodeType.CUSTOM : type;
        metadata = metadata == null ? GraphMetadata.EMPTY : metadata;
    }
}
