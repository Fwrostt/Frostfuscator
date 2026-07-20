package dev.frost.obfuscator.gui.component;

import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

public final class StatusChip extends Label {
    public StatusChip(String text, String tone) {
        super(text);
        getStyleClass().addAll("status-chip", "status-" + tone);
        setMinWidth(Region.USE_PREF_SIZE);
        setMaxWidth(Region.USE_PREF_SIZE);
        setAccessibleText(text + " status");
        String explanation = switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "error", "failed" -> "Blocks a successful validation or build.";
            case "warning" -> "May cause compatibility or runtime issues.";
            case "recommendation", "recommended" -> "Suggested improvement based on project analysis.";
            case "success", "ready" -> "No blocking issue was detected.";
            default -> text + " status";
        };
        setTooltip(new Tooltip(explanation));
    }
}
