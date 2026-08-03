package dev.frost.ir.pass;

import dev.frost.ir.analysis.AnalysisManager;
import java.util.Objects;
import java.util.SplittableRandom;

/** Explicit per-run services. Randomness is deterministic and never hidden in IR objects. */
public final class PassContext {
    private final AnalysisManager analyses;
    private final long seed;

    public PassContext(AnalysisManager analyses, long seed) {
        this.analyses = Objects.requireNonNull(analyses, "analyses");
        this.seed = seed;
    }

    public AnalysisManager analyses() { return analyses; }
    public long seed() { return seed; }
    public SplittableRandom randomFor(String passId) {
        Objects.requireNonNull(passId, "passId");
        return new SplittableRandom(seed ^ mix(passId.hashCode()));
    }

    private long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
