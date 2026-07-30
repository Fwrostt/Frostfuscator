package dev.frost.graph;

import java.util.*;

/** Limit-aware collector that deduplicates edges and produces deterministic graphs. */
public final class GraphCollector {
    private final String id;
    private final String title;
    private final GraphType type;
    private final GraphOptions options;
    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final Map<String, GraphEdge> edges = new LinkedHashMap<>();
    private final List<GraphWarning> warnings = new ArrayList<>();
    private GraphMetadata metadata = GraphMetadata.EMPTY;
    private boolean truncated;

    public GraphCollector(String id, String title, GraphType type, GraphOptions options) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = title;
        this.type = type;
        this.options = options == null ? GraphOptions.defaults() : options;
    }

    public boolean addNode(GraphNode node) {
        if (!options.nodeTypes().isEmpty() && !options.nodeTypes().contains(node.type())) return false;
        if (nodes.containsKey(node.id())) return true;
        if (options.focusNode() == null && nodes.size() >= options.maximumNodes()) {
            markTruncated("node-limit", "Graph node limit of " + options.maximumNodes() + " was reached");
            return false;
        }
        nodes.put(node.id(), node);
        return true;
    }

    public boolean addEdge(GraphEdge edge) {
        if (!options.edgeTypes().isEmpty() && !options.edgeTypes().contains(edge.type())) return false;
        if (edges.containsKey(edge.id())) return true;
        if (options.focusNode() == null && edges.size() >= options.maximumEdges()) {
            markTruncated("edge-limit", "Graph edge limit of " + options.maximumEdges() + " was reached");
            return false;
        }
        if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) return false;
        edges.put(edge.id(), edge);
        return true;
    }

    public void warning(GraphWarning warning) {
        if (warning != null) warnings.add(warning);
    }

    public void metadata(GraphMetadata metadata) {
        this.metadata = metadata == null ? GraphMetadata.EMPTY : metadata;
    }

    public Graph build() {
        Collection<GraphNode> selectedNodes = nodes.values();
        Collection<GraphEdge> selectedEdges = edges.values();
        if (options.focusNode() != null) {
            Set<String> focus = resolveFocus(options.focusNode());
            if (!focus.isEmpty()) {
                LinkedHashSet<String> visible = traverse(focus, options.traversalDepth(), selectedEdges,
                        options.traversalDirection());
                if (visible.size() > options.maximumNodes()) {
                    visible = visible.stream().limit(options.maximumNodes())
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                    markTruncated("node-limit", "Focused graph node limit of " + options.maximumNodes() + " was reached");
                }
                Set<String> selectedIds = visible;
                selectedNodes = selectedNodes.stream().filter(node -> selectedIds.contains(node.id())).toList();
                List<GraphEdge> focusedEdges = selectedEdges.stream()
                        .filter(edge -> selectedIds.contains(edge.source()) && selectedIds.contains(edge.target())).toList();
                if (focusedEdges.size() > options.maximumEdges()) {
                    focusedEdges = focusedEdges.subList(0, options.maximumEdges());
                    markTruncated("edge-limit", "Focused graph edge limit of " + options.maximumEdges() + " was reached");
                }
                selectedEdges = focusedEdges;
            } else {
                warning(new GraphWarning(GraphWarning.Severity.WARNING, "focus-not-found",
                        "Focus node was not found: " + options.focusNode(), GraphMetadata.EMPTY));
            }
        }
        if (options.hideIsolatedNodes()) {
            Set<String> connected = new HashSet<>();
            for (GraphEdge edge : selectedEdges) {
                connected.add(edge.source());
                connected.add(edge.target());
            }
            selectedNodes = selectedNodes.stream().filter(node -> connected.contains(node.id())).toList();
        }
        return new Graph(id, title, type, List.copyOf(selectedNodes), List.copyOf(selectedEdges),
                metadata, warnings, truncated);
    }

    private Set<String> resolveFocus(String requested) {
        if (nodes.containsKey(requested)) return Set.of(requested);
        String normalized = requested.replace('.', '/');
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        nodes.values().stream().filter(node -> requested.equals(node.label())
                        || requested.equals(node.metadata().string("internalName", ""))
                        || normalized.equals(node.metadata().string("internalName", ""))
                        || requested.equals(node.metadata().string("owner", ""))
                        || normalized.equals(node.metadata().string("owner", ""))
                        || requested.equals(node.metadata().string("qualifiedName", "")))
                .map(GraphNode::id).forEach(matches::add);
        return matches;
    }

    private static LinkedHashSet<String> traverse(Set<String> focus, int depth, Collection<GraphEdge> edges,
                                                   TraversalDirection direction) {
        Map<String, Set<String>> adjacent = new HashMap<>();
        for (GraphEdge edge : edges) {
            if (direction != TraversalDirection.INCOMING)
                adjacent.computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>()).add(edge.target());
            if (direction != TraversalDirection.OUTGOING)
                adjacent.computeIfAbsent(edge.target(), ignored -> new LinkedHashSet<>()).add(edge.source());
        }
        LinkedHashSet<String> visible = new LinkedHashSet<>();
        Map<String, Integer> distances = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String item : focus) { queue.add(item); distances.put(item, 0); }
        while (!queue.isEmpty()) {
            String current = queue.removeFirst(); int currentDepth = distances.get(current); visible.add(current);
            if (currentDepth >= depth) continue;
            for (String next : adjacent.getOrDefault(current, Set.of())) if (!distances.containsKey(next)) {
                distances.put(next, currentDepth + 1); queue.addLast(next);
            }
        }
        return visible;
    }

    private void markTruncated(String code, String message) {
        if (truncated) return;
        truncated = true;
        warnings.add(new GraphWarning(GraphWarning.Severity.WARNING, code, message,
                GraphMetadata.builder().put("maximumNodes", options.maximumNodes())
                        .put("maximumEdges", options.maximumEdges()).build()));
    }
}
