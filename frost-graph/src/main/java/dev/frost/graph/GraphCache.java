package dev.frost.graph;

import java.lang.ref.SoftReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Memory-sensitive cache for whole-project and method-level graph results. */
public final class GraphCache {
    public static final int DEFAULT_MAX_ENTRIES =
            Math.max(1, Integer.getInteger("frost.graph.cache.maxEntries", 256));

    private final int maxEntries;
    private final LinkedHashMap<String, SoftReference<Graph>> entries;

    public GraphCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public GraphCache(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SoftReference<Graph>> eldest) {
                return size() > GraphCache.this.maxEntries;
            }
        };
    }

    public synchronized Optional<Graph> get(String key) {
        if (key == null) return Optional.empty();
        SoftReference<Graph> reference = entries.get(key);
        Graph graph = reference == null ? null : reference.get();
        if (reference != null && graph == null) entries.remove(key);
        return Optional.ofNullable(graph);
    }

    public synchronized void put(String key, Graph graph) {
        if (key != null && graph != null) entries.put(key, new SoftReference<>(graph));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        entries.values().removeIf(reference -> reference.get() == null);
        return entries.size();
    }

    public int maxEntries() {
        return maxEntries;
    }
}
