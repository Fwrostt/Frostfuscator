package dev.frost.graph.transform;

import dev.frost.graph.GraphMetadata;
import java.util.*;

/** Neutral transformer metadata safe to expose to plugins and renderers. */
public record TransformerDescriptor(String id, String displayName, boolean enabled, int priority,
                                    String phase, Set<String> dependencies, Set<String> conflicts,
                                    List<String> inclusions, List<String> exclusions,
                                    GraphMetadata configuration) {
    public TransformerDescriptor {
        id = Objects.requireNonNull(id, "id");
        displayName = displayName == null ? id : displayName;
        phase = phase == null ? "NORMAL" : phase;
        dependencies = dependencies == null ? Set.of() : Set.copyOf(dependencies);
        conflicts = conflicts == null ? Set.of() : Set.copyOf(conflicts);
        inclusions = inclusions == null ? List.of() : List.copyOf(inclusions);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        configuration = configuration == null ? GraphMetadata.EMPTY : configuration;
    }
}
