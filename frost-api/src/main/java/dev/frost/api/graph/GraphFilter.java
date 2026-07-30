package dev.frost.api.graph;

import dev.frost.graph.*;

public interface GraphFilter {
    String id();
    String displayName();
    boolean include(GraphNode node);
    default boolean include(GraphEdge edge) { return true; }
}
