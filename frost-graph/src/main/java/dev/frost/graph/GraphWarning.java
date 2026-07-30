package dev.frost.graph;

/** Structured non-fatal graph generation diagnostic. */
public record GraphWarning(Severity severity, String code, String message, GraphMetadata context) {
    public GraphWarning {
        severity = severity == null ? Severity.WARNING : severity;
        code = code == null ? "analysis-warning" : code;
        message = message == null ? "Graph analysis produced a warning" : message;
        context = context == null ? GraphMetadata.EMPTY : context;
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
