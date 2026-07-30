package dev.frost.graph.transform;

import java.util.*;

public record TransformationDiff(Set<String> before, Set<String> after, String transformerId) {
    public TransformationDiff {
        before = before == null ? Set.of() : Set.copyOf(before);
        after = after == null ? Set.of() : Set.copyOf(after);
        transformerId = transformerId == null ? "unknown" : transformerId;
    }
}
