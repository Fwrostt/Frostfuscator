package dev.frost.ir.verify;

import dev.frost.ir.core.Diagnostic;
import java.util.List;

public record ValidationReport(List<Diagnostic> diagnostics) {
    public ValidationReport {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean isValid() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR
                || diagnostic.severity() == Diagnostic.Severity.FATAL);
    }

    public long errorCount() {
        return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR
                || diagnostic.severity() == Diagnostic.Severity.FATAL).count();
    }

    public void throwIfInvalid() {
        if (!isValid()) throw new InvalidIrException(this);
    }
}
