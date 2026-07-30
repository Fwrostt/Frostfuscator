package dev.frost.graph.export;

import dev.frost.graph.Graph;

/** Converts a neutral graph into a textual interchange or renderer format. */
public interface GraphExporter {
    String format();

    String fileExtension();

    String mediaType();

    String export(Graph graph);
}
