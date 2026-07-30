package dev.frost.graph.transform;

import dev.frost.graph.GraphMetadata;

public record GeneratedMember(String owner, String name, String descriptor, String transformerId, GraphMetadata metadata) {
    public GeneratedMember {
        metadata = metadata == null ? GraphMetadata.EMPTY : metadata;
        transformerId = transformerId == null ? "unknown" : transformerId;
    }
}
