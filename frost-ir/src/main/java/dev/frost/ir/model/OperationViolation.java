package dev.frost.ir.model;

import java.util.Objects;

public record OperationViolation(String code, String message) {
    public OperationViolation {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        if (code.isBlank() || message.isBlank()) throw new IllegalArgumentException("violation fields must be non-blank");
    }
}
