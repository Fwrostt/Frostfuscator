package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

public final class ResourcesPage implements PageView {
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);

    public ResourcesPage(AppContext context) {
        this.context = context;
        SmoothScroll.install(root, context.themeManager());
        ObfuscationConfig config = context.projectState().configuration();
        content.getStyleClass().addAll("page", "resources-page");
        content.setPadding(Ui.pageInsets());

        CustomComboBox<String> dictionary = new CustomComboBox<>(List.of("alphabet", "unicode", "numeric"));
        dictionary.setValue(config.getDictionary());
        CustomComboBox<String> packageMode = new CustomComboBox<>(List.of("keep", "flatten", "remove"));
        packageMode.setValue(config.getPackageMode());
        TextField flatten = field(config.getFlattenPackage(), "obf");
        flatten.disableProperty().bind(packageMode.valueProperty().isNotEqualTo("flatten"));
        CheckBox mapping = new CheckBox("Export a mapping file");
        mapping.setSelected(config.getMapping().isEnabled());
        TextField mappingOutput = field(config.getMapping().getOutput(), "mapping.txt");
        mappingOutput.disableProperty().bind(mapping.selectedProperty().not());

        VBox naming = Ui.section("Naming & mappings",
                "These values are suggested after JAR analysis and remain compatible with existing configuration files.",
                Ui.fieldRow("Dictionary", dictionary),
                Ui.fieldRow("Package mode", packageMode),
                Ui.fieldRow("Flatten package", flatten),
                mapping,
                Ui.fieldRow("Mapping output", mappingOutput));

        TextArea inclusions = area(String.join("\n", config.getInclusions()),
                "Optional regex rules that limit processing to matching classes");
        TextArea exclusions = area(String.join("\n", config.getExclusions()),
                "Regex rules for classes that must remain unchanged");
        FlowPane suggested = new FlowPane(Ui.SPACE_2, Ui.SPACE_2);
        context.projectState().analysis().exclusions().forEach(rule -> {
            Button chip = new Button("+ " + rule);
            chip.getStyleClass().add("rule-chip");
            chip.setOnAction(event -> addLine(exclusions, rule));
            suggested.getChildren().add(chip);
        });
        VBox rules = Ui.section("Class rules",
                "Keep reflection, serialization, plugins, service providers, and framework entrypoints safe.",
                Ui.fieldRow("Inclusions", inclusions),
                Ui.fieldRow("Exclusions", exclusions),
                Ui.label("Suggested by analysis", "field-label"),
                suggested.getChildren().isEmpty()
                        ? Ui.label("Analyze an input JAR to receive project-specific rules.", "empty-state-copy")
                        : suggested);

        TextField pluginPaths = areaField(String.join(System.lineSeparator(), config.getPlugins()));
        VBox plugins = Ui.section("Plugins & resource paths",
                "Optional transformer plugins remain part of the existing YAML configuration.",
                Ui.fieldRow("Plugin paths", pluginPaths));

        content.getChildren().addAll(
                Ui.pageHeader("Resources", "Control naming, mappings, rules, and resource-safe behavior without editing YAML."),
                naming, rules, plugins);

        dictionary.valueProperty().addListener((obs, old, value) -> { config.setDictionary(value); touch(); });
        packageMode.valueProperty().addListener((obs, old, value) -> { config.setPackageMode(value); touch(); });
        flatten.textProperty().addListener((obs, old, value) -> { config.setFlattenPackage(value.trim()); touch(); });
        mapping.selectedProperty().addListener((obs, old, value) -> { config.getMapping().setEnabled(value); touch(); });
        mappingOutput.textProperty().addListener((obs, old, value) -> { config.getMapping().setOutput(value.trim()); touch(); });
        inclusions.textProperty().addListener((obs, old, value) -> { config.setInclusions(lines(inclusions)); touch(); });
        exclusions.textProperty().addListener((obs, old, value) -> { config.setExclusions(lines(exclusions)); touch(); });
        pluginPaths.textProperty().addListener((obs, old, value) ->
                config.setPlugins(value.lines().map(String::trim).filter(line -> !line.isBlank()).toList()));
    }

    private void touch() {
        context.projectState().profileProperty().set("Custom");
        context.projectState().touch();
    }

    private static TextField field(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.getStyleClass().add("text-input");
        field.setPromptText(prompt);
        return field;
    }

    private static TextArea area(String value, String prompt) {
        TextArea area = new TextArea(value);
        area.getStyleClass().add("text-area");
        area.setPromptText(prompt);
        area.setPrefRowCount(5);
        return area;
    }

    private static TextField areaField(String value) {
        TextField field = field(value, "One or more plugin paths");
        return field;
    }

    private static List<String> lines(TextArea area) {
        return area.getText().lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    private static void addLine(TextArea area, String value) {
        List<String> current = new java.util.ArrayList<>(lines(area));
        if (!current.contains(value)) current.add(value);
        area.setText(String.join(System.lineSeparator(), current));
    }

    @Override
    public Node root() { return root; }
}
