package dev.frost.ir.pass;

import java.util.Map;

public record PassExecution(String passId, long beforeRevision, long afterRevision,
                            boolean changed, long elapsedNanos, Map<String, Long> metrics) {
    public PassExecution { metrics = Map.copyOf(metrics); }
}
