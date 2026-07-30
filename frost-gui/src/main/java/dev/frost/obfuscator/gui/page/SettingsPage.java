package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.LinkedSlider;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.gui.theme.ThemeDefinition;
import dev.frost.obfuscator.gui.theme.ThemeManager;
import dev.frost.obfuscator.plugin.LoadedPlugin;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;

public final class SettingsPage implements PageView {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);
    private final FlowPane themeChoices = new FlowPane(Ui.SPACE_3, Ui.SPACE_3);
    private final Map<String, TextField> tokenFields = new LinkedHashMap<>();
    private final VBox preview = new VBox(Ui.SPACE_3);
    private Label storagePath;
    private Label storageStatus;
    private Button resetStorage;
    private final TableView<LoadedPlugin> pluginTable = new TableView<>();
    private final Label pluginStatus = Ui.label("Scanning the plugin directory…", "section-description");
    private final BooleanProperty pluginOperationRunning = new SimpleBooleanProperty();

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
                pluginsSection(),
                storageSection());
        refreshThemes();
        refreshPreview();
    }

    @SuppressWarnings("unchecked")
    private Node pluginsSection() {
        pluginTable.getStyleClass().addAll("analytics-table", "settings-plugin-table");
        pluginTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        pluginTable.setFixedCellSize(40);
        pluginTable.setPrefHeight(230);
        pluginTable.setPlaceholder(Ui.label("No runtime plugins loaded. Add a plugin JAR or place one in the folder below.",
                "empty-state-copy"));

        TableColumn<LoadedPlugin, String> name = pluginColumn("Plugin", 190,
                plugin -> plugin.descriptor().name());
        TableColumn<LoadedPlugin, String> version = pluginColumn("Version", 90,
                plugin -> plugin.descriptor().version());
        TableColumn<LoadedPlugin, String> transformers = pluginColumn("Transformers", 105,
                plugin -> Integer.toString(plugin.transformerCount()));
        TableColumn<LoadedPlugin, String> location = pluginColumn("JAR", 360,
                plugin -> plugin.jarPath().toString());
        pluginTable.getColumns().setAll(name, version, transformers, location);

        Button load = Ui.button("Load plugin JAR…", "primary-button", () ->
                context.dialogs().openPluginJar().ifPresent(path -> runPluginOperation(
                        "Loading " + path.getFileName() + "…", context.pluginRuntimeService().load(path))));
        Button reload = Ui.button("Reload selected", "secondary-button", () -> {
            LoadedPlugin selected = pluginTable.getSelectionModel().getSelectedItem();
            if (selected != null) runPluginOperation("Reloading " + selected.descriptor().name() + "…",
                    context.pluginRuntimeService().reload(selected.jarPath()));
        });
        Button unload = Ui.button("Unload selected", "secondary-button", () -> {
            LoadedPlugin selected = pluginTable.getSelectionModel().getSelectedItem();
            if (selected == null || !context.dialogs().confirm("Unload " + selected.descriptor().name() + "?",
                    "Its transformers and event listeners will be removed immediately.", "Unload plugin")) return;
            runPluginOperation("Unloading " + selected.descriptor().name() + "…",
                    context.pluginRuntimeService().unload(selected.jarPath()));
        });
        Button rescan = Ui.button("Rescan folder", "secondary-button", () -> runPluginOperation(
                "Scanning the plugin directory…", context.pluginRuntimeService().scanDefaultDirectory()));

        load.disableProperty().bind(pluginOperationRunning.or(context.projectState().busyProperty()));
        rescan.disableProperty().bind(pluginOperationRunning.or(context.projectState().busyProperty()));
        reload.disableProperty().bind(pluginOperationRunning.or(context.projectState().busyProperty())
                .or(pluginTable.getSelectionModel().selectedItemProperty().isNull()));
        unload.disableProperty().bind(reload.disableProperty());

        HBox actions = new HBox(Ui.SPACE_3, load, reload, unload, rescan);
        actions.setAlignment(Pos.CENTER_LEFT);
        Label directory = Ui.label(context.pluginRuntimeService().pluginDirectory().toString(), "info-value");
        directory.setWrapText(true);
        pluginTable.getItems().setAll(context.pluginRuntimeService().loadedPlugins());
        runPluginOperation("Scanning the plugin directory…", context.pluginRuntimeService().scanDefaultDirectory());
        return Ui.section("Runtime plugins",
                "Load, update, and unload plugin JARs without restarting Frostfuscator. Runtime changes are disabled during builds.",
                pluginTable, actions, Ui.fieldRow("Plugin folder", directory), pluginStatus);
    }

    private TableColumn<LoadedPlugin, String> pluginColumn(String title, double width,
                                                           java.util.function.Function<LoadedPlugin, String> value) {
        TableColumn<LoadedPlugin, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private void runPluginOperation(String pendingMessage, CompletableFuture<List<LoadedPlugin>> operation) {
        pluginOperationRunning.set(true);
        pluginStatus.setText(pendingMessage);
        operation.whenComplete((plugins, failure) -> Platform.runLater(() -> {
            pluginOperationRunning.set(false);
            if (failure != null) {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                pluginStatus.setText("Plugin operation failed: " + cause.getMessage());
                return;
            }
            pluginTable.getItems().setAll(plugins);
            pluginStatus.setText(plugins.isEmpty() ? "No plugins are loaded."
                    : plugins.size() + " plugin" + (plugins.size() == 1 ? "" : "s") + " loaded and active.");
        }));
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
        storagePath = Ui.label(context.preferences().paths().root().toString(), "info-value");
        storagePath.setWrapText(true);
        storagePath.setMaxWidth(Double.MAX_VALUE);
        storageStatus = Ui.label(
                "New build logs are saved in logs/builds. Every crash gets its own file in logs/crashes.",
                "section-description");
        storageStatus.setWrapText(true);
        Button choose = Ui.button("Choose folder", "secondary-button", this::chooseStorageFolder);
        resetStorage = Ui.button("Use default", "secondary-button", this::useDefaultStorage);
        resetStorage.setDisable(context.preferences().paths().root()
                .equals(dev.frost.obfuscator.gui.state.AppDataPaths.systemDefault().root()));
        HBox actions = new HBox(Ui.SPACE_3, choose, resetStorage);
        actions.setAlignment(Pos.CENTER_LEFT);
        return Ui.section("Application data",
                "Preferences, custom themes, the active workspace, recent projects, build history, console output, and crash logs are kept together here.",
                Ui.fieldRow("Storage folder", storagePath), actions, storageStatus);
    }

    private void chooseStorageFolder() {
        context.dialogs().chooseDirectory("Choose Frostfuscator storage folder")
                .ifPresent(this::relocateStorage);
    }

    private void relocateStorage(Path selected) {
        try {
            context.preferences().flush();
            Path next = context.preferences().paths().relocateTo(selected).root();
            storagePath.setText(next.toString());
            storageStatus.setText("Your current data was copied safely. Restart Frostfuscator to use this folder; the old folder remains as a backup.");
            resetStorage.setDisable(next.equals(dev.frost.obfuscator.gui.state.AppDataPaths.systemDefault().root()));
            context.notifications().show("Storage folder saved. Restart Frostfuscator to apply it.");
        } catch (IOException exception) {
            context.dialogs().error("Could not change storage folder", exception);
        }
    }

    private void useDefaultStorage() {
        if (!context.dialogs().confirm("Use the default storage folder?",
                "Current Frostfuscator data will be copied to the default folder. The custom folder will remain as a backup.",
                "Use default")) return;
        try {
            context.preferences().flush();
            Path next = context.preferences().paths().relocateToDefault().root();
            storagePath.setText(next.toString());
            storageStatus.setText("Your current data was copied safely. Restart Frostfuscator to use the default folder.");
            resetStorage.setDisable(true);
            context.notifications().show("Default storage restored. Restart Frostfuscator to apply it.");
        } catch (IOException exception) {
            context.dialogs().error("Could not restore the default storage folder", exception);
        }
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
