package dev.frost.graph;

import java.lang.ref.SoftReference;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Memory-sensitive cache for whole-project and method-level graph results. */
public final class GraphCache {
    private final ConcurrentHashMap<String, SoftReference<Graph>> entries = new ConcurrentHashMap<>();

    public Optional<Graph> get(String key) {
        SoftReference<Graph> reference = entries.get(key);
        Graph graph = reference == null ? null : reference.get();
        if (reference != null && graph == null) entries.remove(key, reference);
        return Optional.ofNullable(graph);
    }

    public void put(String key, Graph graph) {
        if (key != null && graph != null) entries.put(key, new SoftReference<>(graph));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
