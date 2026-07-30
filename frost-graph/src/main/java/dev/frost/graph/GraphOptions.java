package dev.frost.graph;

import java.util.Set;

/** Immutable safety, traversal, aggregation, and filtering options for graph builders. */
public record GraphOptions(int maximumNodes, int maximumEdges, int traversalDepth,
                           boolean includeLibraries, boolean aggregatePackages,
                           boolean hideIsolatedNodes, Set<NodeType> nodeTypes,
                           Set<EdgeType> edgeTypes, String focusNode,
                           TraversalDirection traversalDirection) {
    public static final int DEFAULT_MAXIMUM_NODES = 600;
    public static final int DEFAULT_MAXIMUM_EDGES = 1_800;
    public static final int DEFAULT_TRAVERSAL_DEPTH = 2;

    public GraphOptions {
        maximumNodes = Math.max(1, maximumNodes);
        maximumEdges = Math.max(0, maximumEdges);
        traversalDepth = Math.max(0, traversalDepth);
        nodeTypes = nodeTypes == null ? Set.of() : Set.copyOf(nodeTypes);
        edgeTypes = edgeTypes == null ? Set.of() : Set.copyOf(edgeTypes);
        focusNode = focusNode == null || focusNode.isBlank() ? null : focusNode;
        traversalDirection = traversalDirection == null ? TraversalDirection.BOTH : traversalDirection;
    }

    public static GraphOptions defaults() {
        return new GraphOptions(DEFAULT_MAXIMUM_NODES, DEFAULT_MAXIMUM_EDGES,
                DEFAULT_TRAVERSAL_DEPTH, false, false, false, Set.of(), Set.of(), null,
                TraversalDirection.BOTH);
    }

    public GraphOptions withLimits(int nodes, int edges) {
        return new GraphOptions(nodes, edges, traversalDepth, includeLibraries,
                aggregatePackages, hideIsolatedNodes, nodeTypes, edgeTypes, focusNode, traversalDirection);
    }
}
