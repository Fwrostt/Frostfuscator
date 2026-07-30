package dev.frost.graph.export;

import dev.frost.graph.*;

import java.util.EnumMap;
import java.util.Map;

/** The only Frostfuscator component that emits Mermaid syntax. */
public final class MermaidGraphExporter implements GraphExporter {
    private static final Map<EdgeType, String> ARROWS = arrows();

    @Override
    public String format() {
        return "mermaid";
    }

    @Override
    public String fileExtension() {
        return "mmd";
    }

    @Override
    public String mediaType() {
        return "text/vnd.mermaid";
    }

    @Override
    public String export(Graph graph) {
        StringBuilder output = new StringBuilder(256 + graph.nodes().size() * 64 + graph.edges().size() * 48);
        output.append("flowchart TD\n");
        for (GraphNode node : graph.nodes()) {
            String rendererId = GraphIds.rendererId(node.id());
            output.append("  ").append(rendererId).append("[\"")
                    .append(GraphSanitizer.mermaidLabel(node.label())).append("\"]\n");
        }
        for (GraphEdge edge : graph.edges()) {
            String source = GraphIds.rendererId(edge.source());
            String target = GraphIds.rendererId(edge.target());
            String arrow = ARROWS.getOrDefault(edge.type(), "-->");
            output.append("  ").append(source).append(' ').append(arrow);
            if (!edge.label().isBlank()) {
                output.append("|\"").append(GraphSanitizer.mermaidLabel(edge.label())).append("\"|");
            }
            output.append(' ').append(target).append('\n');
        }
        appendClasses(output);
        for (GraphNode node : graph.nodes()) {
            output.append("  class ").append(GraphIds.rendererId(node.id())).append(' ')
                    .append(cssClass(node.type())).append("\n");
        }
        return output.toString();
    }

    private static void appendClasses(StringBuilder output) {
        output.append("  classDef classNode fill:#15242b,stroke:#7dd3fc,color:#e4e4e7\n")
                .append("  classDef libraryNode fill:#121212,stroke:#52525b,color:#a1a1aa\n")
                .append("  classDef methodNode fill:#101820,stroke:#6fa7dd,color:#e4e4e7\n")
                .append("  classDef blockNode fill:#121212,stroke:#71717a,color:#e4e4e7\n")
                .append("  classDef warningNode fill:#201807,stroke:#e3a934,color:#e4e4e7\n")
                .append("  classDef transformerNode fill:#0a1c16,stroke:#45c99a,color:#e4e4e7\n")
                .append("  classDef defaultNode fill:#121212,stroke:#52525b,color:#e4e4e7\n");
    }

    private static String cssClass(NodeType type) {
        return switch (type) {
            case CLASS -> "classNode";
            case LIBRARY_CLASS -> "libraryNode";
            case METHOD -> "methodNode";
            case BASIC_BLOCK, EXCEPTION_HANDLER, UNREACHABLE_BLOCK -> "blockNode";
            case WARNING -> "warningNode";
            case TRANSFORMER, PIPELINE_PHASE, BUILD_STEP, VERIFICATION -> "transformerNode";
            default -> "defaultNode";
        };
    }

    private static Map<EdgeType, String> arrows() {
        Map<EdgeType, String> arrows = new EnumMap<>(EdgeType.class);
        arrows.put(EdgeType.CONFLICTS, "-.->");
        arrows.put(EdgeType.EXCEPTION, "-.->");
        arrows.put(EdgeType.LOOP_BACK, "==>");
        arrows.put(EdgeType.EXTENDS, "-->");
        arrows.put(EdgeType.IMPLEMENTS, "-->");
        return arrows;
    }
}
