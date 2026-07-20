package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.LinkedSlider;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.gui.theme.ThemeDefinition;
import dev.frost.obfuscator.gui.theme.ThemeManager;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class SettingsPage implements PageView {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);
    private final FlowPane themeChoices = new FlowPane(Ui.SPACE_3, Ui.SPACE_3);
    private final Map<String, TextField> tokenFields = new LinkedHashMap<>();
    private final VBox preview = new VBox(Ui.SPACE_3);

    public SettingsPage(AppContext context) {
        this.context = context;
        SmoothScroll.install(root, context.themeManager());
        content.getStyleClass().addAll("page", "settings-page");
        content.setPadding(Ui.pageInsets());
        content.getChildren().addAll(
                Ui.pageHeader("Settings", "Tune appearance, comfort, motion, and persistent workspace preferences."),
                themesSection(),
                customThemeSection(),
                comfortSection(),
                storageSection());
        refreshThemes();
        refreshPreview();
    }

    private Node themesSection() {
        context.themeManager().activeThemeProperty().addListener((obs, old, value) -> {
            refreshThemes();
            loadThemeIntoEditor(value);
        });
        return Ui.section("Themes",
                "Choose a low-glare built-in theme. Your selection persists across sessions.", themeChoices);
    }

    private Node customThemeSection() {
        ThemeDefinition active = context.themeManager().activeTheme();
        TextField name = new TextField(active.builtIn() ? "My custom theme" : active.displayName());
        name.getStyleClass().add("text-input");
        GridPane tokens = new GridPane();
        tokens.setHgap(Ui.SPACE_4);
        tokens.setVgap(Ui.SPACE_3);
        int row = 0;
        for (String token : List.of("bg", "surface", "surface-raised", "border", "text", "text-muted",
                "accent", "success", "warning", "error", "info")) {
            TextField value = new TextField(active.token(token));
            value.getStyleClass().add("text-input");
            Region swatch = new Region();
            swatch.getStyleClass().add("color-swatch");
            swatch.setStyle("-fx-background-color:" + active.token(token) + ";");
            value.textProperty().addListener((obs, old, next) -> {
                if (HEX.matcher(next).matches()) {
                    swatch.setStyle("-fx-background-color:" + next + ";");
                    value.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), false);
                    refreshPreview();
                } else {
                    value.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), true);
                }
            });
            tokenFields.put(token, value);
            HBox field = new HBox(Ui.SPACE_2, swatch, value);
            HBox.setHgrow(value, Priority.ALWAYS);
            tokens.add(Ui.label(pretty(token), "field-label"), 0, row);
            tokens.add(field, 1, row++);
        }
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(130);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        tokens.getColumnConstraints().addAll(labelColumn, fieldColumn);

        preview.getStyleClass().add("theme-preview");
        preview.setPrefHeight(250);
        HBox editor = new HBox(Ui.SPACE_8, tokens, preview);
        HBox.setHgrow(tokens, Priority.ALWAYS);
        HBox.setHgrow(preview, Priority.ALWAYS);
        preview.setMinWidth(360);
        Button save = Ui.button("Save custom theme", "primary-button", () -> saveTheme(name.getText()));
        Button reset = Ui.button("Reset editor", "secondary-button", () -> loadThemeIntoEditor(context.themeManager().activeTheme()));
        HBox actions = new HBox(Ui.SPACE_3, save, reset);
        return Ui.section("Custom theme",
                "Edit semantic tokens, preview them, name the result, and save it for future sessions.",
                Ui.fieldRow("Theme name", name), editor, actions);
    }

    private Node comfortSection() {
        CustomComboBox<ThemeManager.Density> density =
                new CustomComboBox<>(List.of(ThemeManager.Density.values()), value -> pretty(value.name()));
        density.setValue(context.themeManager().densityProperty().get());
        LinkedSlider fontScale = new LinkedSlider(
                (int) Math.round(context.themeManager().fontScaleProperty().get() * 100),
                85, 135, 5, "%", 100);
        CheckBox reducedMotion = new CheckBox("Reduce navigation and panel motion");
        reducedMotion.setSelected(context.themeManager().reducedMotionProperty().get());
        density.valueProperty().addListener((obs, old, value) -> context.themeManager().densityProperty().set(value));
        fontScale.valueProperty().addListener((obs, old, value) ->
                context.themeManager().fontScaleProperty().set(value.doubleValue() / 100d));
        reducedMotion.selectedProperty().addListener((obs, old, value) ->
                context.themeManager().reducedMotionProperty().set(value));
        return Ui.section("Comfort & motion",
                "These preferences change the whole workspace and persist automatically.",
                Ui.fieldRow("UI density", density),
                Ui.fieldRow("Font scale", fontScale),
                reducedMotion,
                Ui.label("Motion uses short, interruptible transitions for state and orientation. Reduced motion keeps the feedback without movement.",
                        "section-description"));
    }

    private Node storageSection() {
        Label path = Ui.label(context.preferences().paths().root().toString(), "info-value");
        path.setWrapText(true);
        path.setMaxWidth(Double.MAX_VALUE);
        return Ui.section("Application data",
                "Preferences, custom themes, the active workspace, recent projects, build history, console output, and crash logs are kept together here.",
                Ui.fieldRow("Storage folder", path));
    }

    private void refreshThemes() {
        themeChoices.getChildren().clear();
        for (ThemeDefinition theme : context.themeManager().availableThemes()) {
            boolean selected = theme.id().equals(context.themeManager().activeTheme().id());
            Region background = swatch(theme.token("bg"));
            Region surface = swatch(theme.token("surface-raised"));
            Region accent = swatch(theme.token("accent"));
            HBox palette = new HBox(Ui.SPACE_1, background, surface, accent);
            Label name = Ui.label(theme.displayName(), "theme-choice-title");
            Button button = new Button();
            button.setGraphic(new VBox(Ui.SPACE_3, palette, name));
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.getStyleClass().add("theme-choice");
            button.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), selected);
            button.setOnAction(event -> context.themeManager().select(theme.id()));
            themeChoices.getChildren().add(button);
        }
    }

    private void loadThemeIntoEditor(ThemeDefinition theme) {
        tokenFields.forEach((token, field) -> field.setText(theme.token(token)));
        refreshPreview();
    }

    private void refreshPreview() {
        if (preview == null || tokenFields.isEmpty()) return;
        String bg = token("bg", "#000000");
        String surface = token("surface-raised", "#111820");
        String border = token("border", "#243040");
        String text = token("text", "#E6EAF0");
        String muted = token("text-muted", "#9AA7B8");
        String accent = token("accent", "#79A9E8");
        preview.setStyle("-fx-background-color:" + bg + ";-fx-border-color:" + border + ";");
        Label heading = new Label("Theme preview");
        heading.setStyle("-fx-text-fill:" + text + ";-fx-font-size:18px;-fx-font-weight:700;");
        Label copy = new Label("A calm workspace with semantic colors and readable supporting text.");
        copy.setWrapText(true);
        copy.setStyle("-fx-text-fill:" + muted + ";");
        Button action = new Button("Primary action");
        action.setStyle("-fx-background-color:" + surface + ";-fx-border-color:" + accent + ";-fx-text-fill:" + text + ";");
        HBox states = new HBox(Ui.SPACE_2,
                previewChip("Ready", token("success", "#45C99A")),
                previewChip("Warning", token("warning", "#E3A934")),
                previewChip("Error", token("error", "#E36A6F")));
        preview.getChildren().setAll(heading, copy, action, states);
    }

    private void saveTheme(String name) {
        if (name == null || name.isBlank()) {
            context.notifications().show("Enter a name for the custom theme");
            return;
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        for (Map.Entry<String, TextField> entry : tokenFields.entrySet()) {
            String value = entry.getValue().getText().trim();
            if (!HEX.matcher(value).matches()) {
                context.notifications().show("Every theme color must use #RRGGBB format");
                return;
            }
            tokens.put(entry.getKey(), value);
        }
        context.themeManager().saveCustom(name, tokens);
        context.themeManager().accentProperty().set(tokens.get("accent"));
        context.notifications().show("Custom theme saved");
    }

    private String token(String name, String fallback) {
        TextField field = tokenFields.get(name);
        return field != null && HEX.matcher(field.getText().trim()).matches() ? field.getText().trim() : fallback;
    }

    private static Region swatch(String color) {
        Region swatch = new Region();
        swatch.getStyleClass().add("theme-swatch");
        swatch.setStyle("-fx-background-color:" + color + ";");
        return swatch;
    }

    private static Label previewChip(String text, String color) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill:" + color + ";-fx-border-color:" + color + ";-fx-padding:5 9;");
        return label;
    }

    private static String pretty(String value) {
        String lower = value.replace('-', ' ').replace('_', ' ').toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    @Override
    public Node root() { return root; }
}
