package dev.frost.api.graph;

import dev.frost.graph.export.GraphExporter;

public interface GraphExportType extends GraphExporter {
    String displayName();
    default String fileExtension() { return format(); }
}
