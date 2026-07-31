package dev.frost.obfuscator.gui.protection;

import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.LinkedSlider;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.dialog.TargetPickerActions;
import dev.frost.obfuscator.gui.state.ProjectState;
import dev.frost.obfuscator.transformer.TransformerConfig;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

public final class SchemaSettingsRenderer {

    public Node render(AppContext context, ProjectState state, String transformerName) {
        TransformerConfig config = state.configuration().getTransformers()
                .computeIfAbsent(transformerName, key -> new TransformerConfig());
        TransformerConfig recommended = ProtectionProfiles.recommended(state.profileProperty().get(), transformerName);
        TransformerSchema schema = TransformerSchema.infer(transformerName, config, recommended);

        VBox common = new VBox(Ui.SPACE_4);
        VBox advanced = new VBox(Ui.SPACE_4);
        for (SettingSchema setting : schema.settings()) {
            Node row = row(state, config, recommended, setting);
            (setting.advanced() ? advanced : common).getChildren().add(row);
        }
        if (common.getChildren().isEmpty()) {
            common.getChildren().add(Ui.label("This transformer has no additional settings.", "empty-state-copy"));
        }
        VBox root = new VBox(Ui.SPACE_6, common);
        if (!advanced.getChildren().isEmpty()) {
            TitledPane disclosure = new TitledPane("Advanced settings", advanced);
            disclosure.getStyleClass().add("advanced-disclosure");
            disclosure.setExpanded(false);
            root.getChildren().add(disclosure);
        }
        root.getChildren().add(targetScope(context, state, config));
        return root;
    }

    private Node targetScope(AppContext context, ProjectState state, TransformerConfig config) {
        TextArea inclusions = targetArea(config.getInclusions(),
                "Optional regex rules limiting this transformer to matching classes");
        TextArea exclusions = targetArea(config.getExclusions(),
                "Regex rules this transformer must skip");
        VBox inclusionEditor = new VBox(Ui.SPACE_2, inclusions,
                TargetPickerActions.regexTargets(context, inclusions, "Add transformer inclusions"));
        VBox exclusionEditor = new VBox(Ui.SPACE_2, exclusions,
                TargetPickerActions.regexTargets(context, exclusions, "Add transformer exclusions"));
        inclusions.textProperty().addListener((obs, old, value) -> {
            config.setInclusions(TargetPickerActions.lines(inclusions));
            state.touch();
        });
        exclusions.textProperty().addListener((obs, old, value) -> {
            config.setExclusions(TargetPickerActions.lines(exclusions));
            state.touch();
        });
        VBox content = new VBox(Ui.SPACE_4,
                Ui.label("Apply package or class rules only to this transformer. Project-wide rules still take precedence.",
                        "setting-description"),
                Ui.fieldRow("Inclusions", inclusionEditor),
                Ui.fieldRow("Exclusions", exclusionEditor));
        TitledPane disclosure = new TitledPane("Target scope", content);
        disclosure.getStyleClass().add("advanced-disclosure");
        disclosure.setExpanded(false);
        return disclosure;
    }

    private static TextArea targetArea(List<String> values, String prompt) {
        TextArea area = new TextArea(String.join(System.lineSeparator(), values));
        area.getStyleClass().add("text-area");
        area.setPromptText(prompt);
        area.setPrefRowCount(4);
        return area;
    }

    private Node row(ProjectState state, TransformerConfig config, TransformerConfig recommended, SettingSchema schema) {
        Label title = Ui.label(schema.label(), "setting-title");
        Label description = Ui.label(schema.description(), "setting-description");
        description.setWrapText(true);
        description.setMinWidth(0);
        description.setMaxWidth(Double.MAX_VALUE);
        VBox copy = new VBox(Ui.SPACE_1, title, description);
        copy.setMinWidth(0);
        Node control = control(state, config, schema);
        Button reset = new Button("Reset");
        reset.getStyleClass().add("inline-button");
        reset.setMinWidth(Region.USE_PREF_SIZE);
        reset.setOnAction(event -> {
            Object value = recommended.getOptions().getOrDefault(schema.key(), schema.defaultValue());
            config.getOptions().put(schema.key(), value);
            applyControlValue(control, value);
            state.touch();
        });
        if (control instanceof Region region) {
            region.setMinWidth(0);
            region.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(region, Priority.ALWAYS);
        }
        HBox actions = new HBox(Ui.SPACE_3, control, reset);
        actions.setMinWidth(0);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox row = new VBox(Ui.SPACE_4, copy, actions);
        row.setMinWidth(0);
        row.getStyleClass().add("setting-row");
        return row;
    }

    @SuppressWarnings("unchecked")
    private static void applyControlValue(Node control, Object value) {
        if (control instanceof CheckBox box) {
            box.setSelected(value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value)));
        } else if (control instanceof LinkedSlider slider) {
            int numeric = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            slider.valueProperty().set(numeric);
        } else if (control instanceof CustomComboBox<?> combo) {
            ((CustomComboBox<Object>) combo).setValue(value);
        } else if (control instanceof TextField field) {
            field.setText(String.valueOf(value));
        }
    }

    private Node control(ProjectState state, TransformerConfig config, SettingSchema schema) {
        Object current = config.getOptions().getOrDefault(schema.key(), schema.defaultValue());
        return switch (schema.type()) {
            case BOOLEAN -> {
                CheckBox toggle = new CheckBox();
                toggle.getStyleClass().add("switch");
                toggle.setSelected(current instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(current)));
                toggle.selectedProperty().addListener((obs, old, value) -> {
                    config.getOptions().put(schema.key(), value);
                    state.touch();
                });
                yield toggle;
            }
            case INTEGER -> {
                int value = current instanceof Number number ? number.intValue()
                        : Integer.parseInt(String.valueOf(current));
                LinkedSlider slider = new LinkedSlider(value, schema.min(), schema.max(), schema.step(),
                        schema.unit(), ((Number) schema.defaultValue()).intValue());
                slider.setMinWidth(0);
                slider.setPrefWidth(460);
                slider.setMaxWidth(Double.MAX_VALUE);
                slider.valueProperty().addListener((obs, old, next) -> {
                    config.getOptions().put(schema.key(), next.intValue());
                    state.touch();
                });
                yield slider;
            }
            case CHOICE -> {
                List<String> choices = new ArrayList<>(schema.choices());
                String value = String.valueOf(current);
                if (!choices.contains(value)) choices.addFirst(value);
                CustomComboBox<String> combo = new CustomComboBox<>(choices);
                combo.setValue(value);
                combo.valueProperty().addListener((obs, old, next) -> {
                    config.getOptions().put(schema.key(), next);
                    state.touch();
                });
                combo.setMinWidth(220);
                combo.setPrefWidth(300);
                combo.setMaxWidth(Double.MAX_VALUE);
                yield combo;
            }
            case TEXT -> {
                TextField field = new TextField(String.valueOf(current));
                field.getStyleClass().add("text-input");
                field.setMinWidth(180);
                field.setPrefWidth(360);
                field.setMaxWidth(Double.MAX_VALUE);
                field.textProperty().addListener((obs, old, value) -> {
                    config.getOptions().put(schema.key(), value);
                    state.touch();
                });
                yield field;
            }
        };
    }
}
