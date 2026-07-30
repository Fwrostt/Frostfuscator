package dev.frost.api.graph;

import dev.frost.graph.*;

@FunctionalInterface
public interface GraphMetadataProvider {
    GraphMetadata metadata(Graph graph, GraphNode node);
}
