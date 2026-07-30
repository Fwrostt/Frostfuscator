package dev.frost.api.graph;

import dev.frost.graph.*;

public interface GraphContextAction {
    String id();
    String label();
    default boolean supports(Graph graph, GraphNode node) { return true; }
    void execute(Graph graph, GraphNode node, GraphPluginContext context);
}
