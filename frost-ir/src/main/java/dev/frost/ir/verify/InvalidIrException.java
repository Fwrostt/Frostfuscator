package dev.frost.ir.verify;

public final class InvalidIrException extends IllegalStateException {
    private final ValidationReport report;

    public InvalidIrException(ValidationReport report) {
        super("Frost-IR validation failed with " + report.errorCount() + " error(s): "
                + report.diagnostics().stream().filter(diagnostic -> diagnostic.severity().ordinal() >= 2)
                .findFirst().map(diagnostic -> diagnostic.code() + ": " + diagnostic.message()).orElse("unknown error"));
        this.report = report;
    }

    public ValidationReport report() { return report; }
}
