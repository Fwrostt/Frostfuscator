package dev.frost.ir.analysis;

import dev.frost.ir.model.IrMethod;
import dev.frost.ir.pass.PreservedAnalyses;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Revision-keyed method analysis cache with no global state. */
public final class AnalysisManager {
    private final Map<IrMethod, Map<AnalysisKey<?>, Entry>> cache = new IdentityHashMap<>();

    public synchronized <T> T get(IrMethod method, MethodAnalysis<T> analysis) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(analysis, "analysis");
        Map<AnalysisKey<?>, Entry> methodCache = cache.computeIfAbsent(method, ignored -> new LinkedHashMap<>());
        Entry cached = methodCache.get(analysis.key());
        if (cached != null && cached.revision == method.revision()) {
            return analysis.key().resultType().cast(cached.value);
        }
        T result = Objects.requireNonNull(analysis.compute(method, this), "analysis result");
        if (!analysis.key().resultType().isInstance(result)) {
            throw new IllegalStateException("Analysis " + analysis.key().name() + " returned " + result.getClass());
        }
        methodCache.put(analysis.key(), new Entry(method.revision(), result));
        return result;
    }

    public synchronized void invalidate(IrMethod method, PreservedAnalyses preserved,
                                        long oldRevision, long newRevision) {
        Map<AnalysisKey<?>, Entry> methodCache = cache.get(method);
        if (methodCache == null) return;
        if (preserved.preservesAll()) {
            methodCache.replaceAll((ignored, entry) -> entry.revision == oldRevision
                    ? new Entry(newRevision, entry.value) : entry);
            return;
        }
        methodCache.entrySet().removeIf(entry -> !preserved.preserves(entry.getKey()));
        methodCache.replaceAll((ignored, entry) -> entry.revision == oldRevision
                ? new Entry(newRevision, entry.value) : entry);
    }

    public synchronized void clear(IrMethod method) { cache.remove(method); }
    public synchronized void clearAll() { cache.clear(); }

    private record Entry(long revision, Object value) {}
}
