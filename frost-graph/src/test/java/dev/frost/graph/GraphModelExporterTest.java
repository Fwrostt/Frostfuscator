package dev.frost.graph;

import dev.frost.graph.export.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphModelExporterTest {
    @Test void escapesSpecialLabelsAndNeverUsesSemanticIdsAsRendererSyntax() {
        Graph graph = new Graph("g", "x", GraphType.CUSTOM,
                List.of(new GraphNode("unsafe id;click", "A\"]\n<script>alert('x')</script>", NodeType.CUSTOM, GraphMetadata.EMPTY)),
                List.of(), GraphMetadata.EMPTY, List.of(), false);
        String output = new MermaidGraphExporter().export(graph);
        assertFalse(output.contains("unsafe id;click"));
        assertFalse(output.contains("<script>"));
        assertTrue(output.contains("&quot;") && output.contains("&lt;script&gt;"));
    }

    @Test void orderingAndIdsAreDeterministicAndDuplicateEdgesCollapse() {
        GraphCollector collector = new GraphCollector("g", "g", GraphType.CUSTOM, GraphOptions.defaults());
        collector.addNode(new GraphNode("z", "Z", NodeType.CUSTOM, GraphMetadata.EMPTY));
        collector.addNode(new GraphNode("a", "A", NodeType.CUSTOM, GraphMetadata.EMPTY));
        GraphEdge edge = new GraphEdge(null, "a", "z", EdgeType.CUSTOM, "same", GraphMetadata.EMPTY);
        collector.addEdge(edge); collector.addEdge(new GraphEdge(null, "a", "z", EdgeType.CUSTOM, "same", GraphMetadata.EMPTY));
        Graph graph = collector.build();
        assertEquals(List.of("a", "z"), graph.nodes().stream().map(GraphNode::id).toList());
        assertEquals(1, graph.edges().size());
        assertEquals(new MermaidGraphExporter().export(graph), new MermaidGraphExporter().export(graph));
    }

    @Test void limitsWarnAndJsonRoundTripsAsStructuredData() {
        GraphCollector collector = new GraphCollector("g", "g", GraphType.CUSTOM,
                GraphOptions.defaults().withLimits(1, 0));
        collector.addNode(new GraphNode("a", "A", NodeType.CUSTOM, GraphMetadata.EMPTY));
        collector.addNode(new GraphNode("b", "B", NodeType.CUSTOM, GraphMetadata.EMPTY));
        Graph graph = collector.build();
        assertTrue(graph.truncated()); assertEquals("node-limit", graph.warnings().getFirst().code());
        String json = new JsonGraphExporter().export(graph);
        assertTrue(json.contains("\"nodes\"") && json.contains("\"truncated\": true"));
    }

    @Test void focusAndDepthProduceAStableNeighborhood() {
        GraphCollector collector = new GraphCollector("g", "g", GraphType.CUSTOM,
                new GraphOptions(20, 20, 1, false, false, false, Set.of(), Set.of(), "B",
                        TraversalDirection.BOTH));
        for (String id : List.of("a", "b", "c", "d")) collector.addNode(new GraphNode(id, id.toUpperCase(), NodeType.CUSTOM, GraphMetadata.EMPTY));
        collector.addEdge(new GraphEdge(null, "a", "b", EdgeType.CUSTOM, "", GraphMetadata.EMPTY));
        collector.addEdge(new GraphEdge(null, "b", "c", EdgeType.CUSTOM, "", GraphMetadata.EMPTY));
        collector.addEdge(new GraphEdge(null, "c", "d", EdgeType.CUSTOM, "", GraphMetadata.EMPTY));
        Graph graph = collector.build();
        assertEquals(Set.of("a", "b", "c"), new HashSet<>(graph.nodes().stream().map(GraphNode::id).toList()));
        assertEquals(2, graph.edges().size());
    }

    @Test void focusedProjectionHonorsIncomingAndOutgoingDirectionBeforeLimits() {
        Graph incoming = directedNeighborhood(TraversalDirection.INCOMING);
        assertEquals(Set.of("a", "b"), incoming.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet()));
        Graph outgoing = directedNeighborhood(TraversalDirection.OUTGOING);
        assertEquals(Set.of("b", "c"), outgoing.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet()));
    }

    private static Graph directedNeighborhood(TraversalDirection direction) {
        GraphCollector collector = new GraphCollector("g", "g", GraphType.CUSTOM,
                new GraphOptions(20, 20, 1, false, false, false, Set.of(), Set.of(), "B", direction));
        for (String id : List.of("a", "b", "c"))
            collector.addNode(new GraphNode(id, id.toUpperCase(), NodeType.CUSTOM, GraphMetadata.EMPTY));
        collector.addEdge(new GraphEdge(null, "a", "b", EdgeType.CUSTOM, "", GraphMetadata.EMPTY));
        collector.addEdge(new GraphEdge(null, "b", "c", EdgeType.CUSTOM, "", GraphMetadata.EMPTY));
        return collector.build();
    }
}
