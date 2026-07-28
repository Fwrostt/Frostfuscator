package dev.frost.obfuscator.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelTransformBenchmarkTest {
    @Test
    void classLocalSchedulingReachesTheFourWorkerTargetBand() {
        Assumptions.assumeTrue(Boolean.getBoolean("frost.runBenchmarks"),
                "Timing benchmarks are opt-in; run with -Dfrost.runBenchmarks=true");
        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= 4);
        ClassPool pool = new ClassPool();
        for (int index = 0; index < 160; index++) {
            ClassNode node = new ClassNode();
            node.name = "benchmark/Class" + index;
            pool.addClass(node.name, node);
        }

        pool.configureParallelism(false, 1, 1);
        long sequential = timedWorkload(pool);
        pool.configureParallelism(true, 4, 1);
        long parallel = timedWorkload(pool);
        pool.closeParallelism();

        double speedup = (double) sequential / parallel;
        System.out.printf("Frost class-local scheduler: %.2fx (%d ms sequential, %d ms parallel)%n",
                speedup, TimeUnit.NANOSECONDS.toMillis(sequential), TimeUnit.NANOSECONDS.toMillis(parallel));
        assertTrue(speedup >= 3.0 && speedup <= 5.5,
                () -> "Expected a 3x-5x class-local scheduling improvement, measured " + speedup + "x");
    }

    private long timedWorkload(ClassPool pool) {
        long started = System.nanoTime();
        pool.forEachClass(node -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
        return System.nanoTime() - started;
    }
}
