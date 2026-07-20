package dev.frost.obfuscator.gui.validation;

import dev.frost.obfuscator.gui.state.ProjectState;

import java.util.function.Consumer;

public record Problem(
        Severity severity,
        String id,
        String title,
        String explanation,
        String quickFixLabel,
        Consumer<ProjectState> quickFix
) {
    public enum Severity { ERROR, WARNING, RECOMMENDATION }

    public boolean hasQuickFix() {
        return quickFix != null && quickFixLabel != null && !quickFixLabel.isBlank();
    }
}
