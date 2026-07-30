package dev.frost.api.graph;

import dev.frost.graph.GraphMetadata;
import java.util.*;

/** Sanitized, renderer-free input supplied to plugin graph extensions. */
public record GraphPluginContext(String scope, GraphMetadata metadata, Map<String, byte[]> classBytes) {
    public GraphPluginContext {
        scope = scope == null ? "project" : scope;
        metadata = metadata == null ? GraphMetadata.EMPTY : metadata;
        Map<String, byte[]> safe = new LinkedHashMap<>();
        if (classBytes != null) classBytes.forEach((name, bytes) -> safe.put(name, bytes.clone()));
        classBytes = Collections.unmodifiableMap(safe);
    }
}
