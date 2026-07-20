package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.LinkedSlider;
import dev.frost.obfuscator.gui.component.StatusChip;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.gui.protection.ProtectionProfiles;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;

import java.util.List;

public final class PresetsPage implements PageView {
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);
    private final VBox profiles = new VBox(Ui.SPACE_3);

    public PresetsPage(AppContext context) {
        this.context = context;
        SmoothScroll.install(root, context.themeManager());
        content.getStyleClass().addAll("page", "presets-page");
        content.setPadding(Ui.pageInsets());

        CustomComboBox<String> goal = new CustomComboBox<>(List.of(
                "Best compatibility", "Strongest protection", "Smallest output", "Lowest runtime overhead"));
        goal.setValue(context.projectState().goalProperty().get());
        LinkedSlider sizeLimit = new LinkedSlider((int) context.projectState().outputSizeLimitMbProperty().get(),
                0, 4096, 16, "MB", 0);
        LinkedSlider overhead = new LinkedSlider((int) Math.round(context.projectState().runtimeOverheadPreferenceProperty().get() * 100),
                0, 100, 5, "%", 35);
        goal.valueProperty().addListener((obs, old, value) -> context.projectState().goalProperty().set(value));
        sizeLimit.valueProperty().addListener((obs, old, value) -> {
            context.projectState().outputSizeLimitMbProperty().set(value.doubleValue());
            context.projectState().touch();
        });
        overhead.valueProperty().addListener((obs, old, value) -> {
            context.projectState().runtimeOverheadPreferenceProperty().set(value.doubleValue() / 100d);
            context.projectState().touch();
        });

        VBox goals = Ui.section("Protection goal",
                "Recommendations account for project size, compatibility, output limits, and runtime preference.",
                Ui.fieldRow("Optimize for", goal),
                Ui.fieldRow("Output-size limit", sizeLimit),
                Ui.fieldRow("Allowed overhead", overhead));
        content.getChildren().addAll(
                Ui.pageHeader("Presets", "Start from centralized profiles, then customize individual transformers when needed."),
                Ui.section("Protection profiles", "Each profile applies a complete, versioned transformer definition.", profiles),
                goals);
        context.projectState().profileProperty().addListener((obs, old, value) -> refresh());
        refresh();
    }

    private void refresh() {
        profiles.getChildren().clear();
        for (ProtectionProfiles.Definition definition : ProtectionProfiles.definitions()) {
            boolean selected = definition.name().equalsIgnoreCase(context.projectState().profileProperty().get());
            StatusChip chip = new StatusChip(selected ? "Selected" : definition.strength(),
                    selected ? "info" : "neutral");
            Label title = Ui.label(definition.name(), "profile-title");
            Label description = Ui.label(definition.description(), "profile-description");
            description.setWrapText(true);
            VBox copy = new VBox(Ui.SPACE_1, title, description);
            HBox.setHgrow(copy, Priority.ALWAYS);
            VBox facts = new VBox(Ui.SPACE_1,
                    Ui.label("Compatibility: " + definition.compatibility(), "muted-text"),
                    Ui.label("Output: " + definition.output() + " · Overhead: " + definition.overhead(), "muted-text"));
            Button apply = Ui.button(selected ? "Applied" : "Apply profile",
                    selected ? "secondary-button" : "primary-button",
                    () -> {
                        ProtectionProfiles.apply(context.projectState(), definition.name());
                        context.validationCoordinator().validateNow();
                        context.notifications().show(definition.name() + " profile applied");
                    });
            apply.setDisable(selected);
            HBox row = new HBox(Ui.SPACE_4, chip, copy, facts, apply);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("profile-row");
            profiles.getChildren().add(row);
        }
    }

    @Override
    public Node root() { return root; }
}
