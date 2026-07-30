package dev.frost.api.graph;

import dev.frost.graph.*;

public interface CustomGraphBuilder {
    String id();
    String displayName();
    Graph build(GraphPluginContext source, GraphBuildContext context) throws Exception;
}
