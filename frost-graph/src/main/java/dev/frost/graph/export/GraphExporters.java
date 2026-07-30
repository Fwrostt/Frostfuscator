package dev.frost.graph.export;

import java.util.List;
import java.util.Locale;

/** Registry for built-in headless graph exporters. */
public final class GraphExporters {
    private static final List<GraphExporter> BUILT_INS = List.of(
            new MermaidGraphExporter(), new JsonGraphExporter(), new DotGraphExporter());

    private GraphExporters() {
    }

    public static GraphExporter byFormat(String format) {
        String normalized = format == null ? "mermaid" : format.toLowerCase(Locale.ROOT);
        return BUILT_INS.stream().filter(exporter -> exporter.format().equals(normalized))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported graph format '" + format + "'. Expected mermaid, json, or dot."));
    }

    public static List<GraphExporter> builtIns() {
        return BUILT_INS;
    }
}
