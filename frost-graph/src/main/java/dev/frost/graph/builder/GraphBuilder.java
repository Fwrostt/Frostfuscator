package dev.frost.graph.builder;

import dev.frost.graph.Graph;
import dev.frost.graph.GraphBuildContext;

/** Renderer-independent graph producer. */
@FunctionalInterface
public interface GraphBuilder<S> {
    Graph build(S source, GraphBuildContext context) throws Exception;
}
