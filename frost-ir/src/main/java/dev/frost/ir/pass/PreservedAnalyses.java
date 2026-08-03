package dev.frost.ir.pass;

import dev.frost.ir.analysis.AnalysisKey;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PreservedAnalyses {
    private static final PreservedAnalyses ALL = new PreservedAnalyses(true, Set.of());
    private static final PreservedAnalyses NONE = new PreservedAnalyses(false, Set.of());
    private final boolean all;
    private final Set<AnalysisKey<?>> keys;

    private PreservedAnalyses(boolean all, Set<AnalysisKey<?>> keys) {
        this.all = all;
        this.keys = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }

    public static PreservedAnalyses all() { return ALL; }
    public static PreservedAnalyses none() { return NONE; }
    public static PreservedAnalyses of(AnalysisKey<?>... keys) { return new PreservedAnalyses(false, Set.of(keys)); }
    public boolean preservesAll() { return all; }
    public boolean preserves(AnalysisKey<?> key) { return all || keys.contains(key); }
    public Set<AnalysisKey<?>> keys() { return keys; }
}
