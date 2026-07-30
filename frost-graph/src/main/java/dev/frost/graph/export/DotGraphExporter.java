package dev.frost.graph.export;

import dev.frost.graph.*;

/** Graphviz DOT exporter for environments that support external DOT rendering. */
public final class DotGraphExporter implements GraphExporter {
    @Override
    public String format() {
        return "dot";
    }

    @Override
    public String fileExtension() {
        return "dot";
    }

    @Override
    public String mediaType() {
        return "text/vnd.graphviz";
    }

    @Override
    public String export(Graph graph) {
        StringBuilder output = new StringBuilder("digraph FrostGraph {\n  rankdir=TB;\n");
        for (GraphNode node : graph.nodes()) {
            output.append("  ").append(GraphIds.rendererId(node.id())).append(" [label=\"")
                    .append(GraphSanitizer.dot(node.label())).append("\"];\n");
        }
        for (GraphEdge edge : graph.edges()) {
            output.append("  ").append(GraphIds.rendererId(edge.source())).append(" -> ")
                    .append(GraphIds.rendererId(edge.target()));
            if (!edge.label().isBlank()) {
                output.append(" [label=\"").append(GraphSanitizer.dot(edge.label())).append("\"]");
            }
            output.append(";\n");
        }
        return output.append("}\n").toString();
    }
}
