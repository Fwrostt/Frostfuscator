package dev.frost.obfuscator.engine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassPoolParallelismTest {
    @Test
    void sharedCancellationStopsClassIteration() {
        ClassPool pool = new ClassPool();
        pool.addClass("sample/One", new ClassNode());
        BuildCancellation cancellation = new BuildCancellation();
        pool.setCancellation(cancellation);
        cancellation.cancel();

        assertThrows(CancellationException.class, () -> pool.forEachClass(node -> {
            throw new AssertionError("cancelled work must not start");
        }));
    }

    @Test
    void usesBoundedWorkersAndKeepsMappedResultsOrdered() {
        ClassPool pool = new ClassPool();
        for (int index = 63; index >= 0; index--) {
            ClassNode node = new ClassNode();
            node.name = "sample/C" + String.format("%02d", index);
            pool.addClass(node.name, node);
        }
        pool.configureParallelism(true, 4, 1);
        Set<String> workerNames = ConcurrentHashMap.newKeySet();

        List<String> names = pool.mapClasses(node -> {
            workerNames.add(Thread.currentThread().getName());
            long value = 0;
            for (int round = 0; round < 50_000; round++) value ^= node.name.hashCode() * (long) round;
            if (value == Long.MIN_VALUE) throw new AssertionError("unreachable");
            return node.name;
        });

        int expectedParallelism = Math.min(4, Runtime.getRuntime().availableProcessors());
        assertEquals(Math.max(1, expectedParallelism), pool.transformParallelism());
        if (expectedParallelism > 1) {
            assertTrue(workerNames.stream().anyMatch(name -> name.startsWith("frost-transform-")));
        }
        assertEquals("sample/C00", names.getFirst());
        assertEquals("sample/C63", names.getLast());
        pool.closeParallelism();
    }
}
