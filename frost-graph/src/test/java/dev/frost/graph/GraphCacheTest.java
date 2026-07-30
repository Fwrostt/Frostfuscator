package dev.frost.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphCacheTest {
    @Test
    void evictsLeastRecentlyUsedGraphAtCapacity() {
        GraphCache cache = new GraphCache(2);
        Graph first = graph("first");
        Graph second = graph("second");
        Graph third = graph("third");

        cache.put("first", first);
        cache.put("second", second);
        assertSame(first, cache.get("first").orElseThrow(), "reads must refresh LRU order");

        cache.put("third", third);

        assertTrue(cache.get("second").isEmpty());
        assertSame(first, cache.get("first").orElseThrow());
        assertSame(third, cache.get("third").orElseThrow());
        assertEquals(2, cache.size());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new GraphCache(0));
    }

    private static Graph graph(String id) {
        return new Graph(id, id, GraphType.CUSTOM, List.of(), List.of(), null, List.of(), false);
    }
}
