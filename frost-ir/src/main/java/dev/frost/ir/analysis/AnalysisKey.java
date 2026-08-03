package dev.frost.ir.analysis;

import java.util.Objects;

public record AnalysisKey<T>(String name, Class<T> resultType) {
    public AnalysisKey {
        name = Objects.requireNonNull(name, "name");
        resultType = Objects.requireNonNull(resultType, "resultType");
        if (name.isBlank()) throw new IllegalArgumentException("analysis name must not be blank");
    }
}
