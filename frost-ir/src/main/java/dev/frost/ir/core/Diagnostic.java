package dev.frost.ir.core;

import java.util.Map;
import java.util.Objects;

/** Structured validation, import, lowering, and pass diagnostic. */
public record Diagnostic(Severity severity, String code, String message, IrId entity,
                         SourcePosition position, Map<String, String> details) {
    public Diagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        position = position == null ? SourcePosition.UNKNOWN : position;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public enum Severity { INFO, WARNING, ERROR, FATAL }
}
