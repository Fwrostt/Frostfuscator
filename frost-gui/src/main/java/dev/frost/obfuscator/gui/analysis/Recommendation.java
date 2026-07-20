package dev.frost.obfuscator.gui.analysis;

import dev.frost.obfuscator.gui.state.ProjectState;

import java.util.List;
import java.util.function.Consumer;

public record Recommendation(
        String id,
        String title,
        String explanation,
        String quickFix,
        int priority,
        String category,
        List<String> transformers,
        Consumer<ProjectState> action
) {
    public Recommendation(String id, String title, String explanation, String quickFix, int priority) {
        this(id, title, explanation, quickFix, priority, "Analysis", List.of(), null);
    }

    public boolean actionable() {
        return action != null && quickFix != null && !quickFix.isBlank();
    }
}
