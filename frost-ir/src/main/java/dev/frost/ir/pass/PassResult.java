package dev.frost.ir.pass;

import dev.frost.ir.core.Diagnostic;
import java.util.List;
import java.util.Map;

public record PassResult(boolean changed, PreservedAnalyses preservedAnalyses,
                         List<Diagnostic> diagnostics, Map<String, Long> metrics) {
    public PassResult {
        preservedAnalyses = preservedAnalyses == null ? PreservedAnalyses.none() : preservedAnalyses;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static PassResult unchanged() { return new PassResult(false, PreservedAnalyses.all(), List.of(), Map.of()); }
    public static PassResult modified() { return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of()); }
}
