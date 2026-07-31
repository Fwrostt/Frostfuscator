package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.dialog.TargetPickerActions;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.remapper.MappingFormat;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;

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
        CustomComboBox<String> mappingFormat = new CustomComboBox<>(List.of("yaml", "proguard", "tiny"));
        mappingFormat.setValue(config.getMapping().getFormat());
        mappingFormat.disableProperty().bind(mapping.selectedProperty().not());
        TextField mappingOutput = field(config.getMapping().getOutput(), "mapping.yml");
        mappingOutput.disableProperty().bind(mapping.selectedProperty().not());
        CheckBox encryptMapping = new CheckBox("Encrypt mapping with AES-256");
        encryptMapping.setSelected(config.getMapping().isEncrypted());
        encryptMapping.disableProperty().bind(mapping.selectedProperty().not());
        PasswordField mappingPassword = new PasswordField();
        mappingPassword.getStyleClass().add("text-input");
        mappingPassword.setPromptText("Password (kept in memory only)");
        mappingPassword.disableProperty().bind(mapping.selectedProperty().not().or(encryptMapping.selectedProperty().not()));
        TextField passwordEnvironment = field(config.getMapping().getPasswordEnvironment(),
                "FROST_MAPPING_PASSWORD");
        passwordEnvironment.disableProperty().bind(mapping.selectedProperty().not().or(encryptMapping.selectedProperty().not()));
        Label mappingSecurityCopy = Ui.label(
                "The password is never written to YAML or workspace files. CLI builds can read it from the environment variable.",
                "setting-description");
        mappingSecurityCopy.setWrapText(true);
        mappingSecurityCopy.disableProperty().bind(encryptMapping.selectedProperty().not());

        VBox naming = Ui.section("Naming & mappings",
                "These values are suggested after JAR analysis and remain compatible with existing configuration files.",
                Ui.fieldRow("Dictionary", dictionary),
                Ui.fieldRow("Package mode", packageMode),
                Ui.fieldRow("Flatten package", flatten),
                mapping,
                Ui.fieldRow("Mapping format", mappingFormat),
                Ui.fieldRow("Mapping output", mappingOutput),
                encryptMapping,
                Ui.fieldRow("Mapping password", mappingPassword),
                Ui.fieldRow("CLI password variable", passwordEnvironment),
                mappingSecurityCopy);

        TextArea inclusions = area(String.join("\n", config.getInclusions()),
                "Optional regex rules that limit processing to matching classes");
        TextArea exclusions = area(String.join("\n", config.getExclusions()),
                "Regex rules for classes that must remain unchanged");
        VBox inclusionEditor = new VBox(Ui.SPACE_2, inclusions,
                TargetPickerActions.regexTargets(context, inclusions, "Add project inclusions"));
        VBox exclusionEditor = new VBox(Ui.SPACE_2, exclusions,
                TargetPickerActions.regexTargets(context, exclusions, "Add project exclusions"));

        FlowPane presetPane = new FlowPane(Ui.SPACE_2, Ui.SPACE_2);
        for (dev.frost.obfuscator.config.preset.ExclusionPreset preset : dev.frost.obfuscator.config.preset.ExclusionPreset.values()) {
            String pName = preset.name().toLowerCase(java.util.Locale.ROOT);
            javafx.scene.control.ToggleButton chip = new javafx.scene.control.ToggleButton(preset.getDisplayName());
            chip.setSelected(config.getPresets().contains(pName) || config.getPresets().contains(preset.name()));
            chip.setOnAction(event -> {
                List<String> list = new ArrayList<>(config.getPresets());
                if (chip.isSelected()) {
                    if (!list.contains(pName)) list.add(pName);
                } else {
                    list.remove(pName);
                    list.remove(preset.name());
                }
                config.setPresets(list);
                touch();
            });
            presetPane.getChildren().add(chip);
        }

        FlowPane suggested = new FlowPane(Ui.SPACE_2, Ui.SPACE_2);
        context.projectState().analysis().exclusions().forEach(rule -> {
            Button chip = new Button("+ " + rule);
            chip.getStyleClass().add("rule-chip");
            chip.setOnAction(event -> addLine(exclusions, rule));
            suggested.getChildren().add(chip);
        });
        VBox rules = Ui.section("Class rules & framework presets",
                "Keep reflection, serialization, plugins, service providers, and framework entrypoints safe.",
                Ui.fieldRow("Framework Presets", presetPane),
                Ui.fieldRow("Inclusions", inclusionEditor),
                Ui.fieldRow("Exclusions", exclusionEditor),
                Ui.label("Suggested by analysis", "field-label"),
                suggested.getChildren().isEmpty()
                        ? Ui.label("Analyze an input JAR to receive project-specific rules.", "empty-state-copy")
                        : suggested);

        TextField pluginPaths = areaField(String.join(System.lineSeparator(), config.getPlugins()));
        VBox plugins = Ui.section("Plugins & resource paths",
                "Optional transformer plugins remain part of the existing YAML configuration.",
                Ui.fieldRow("Plugin paths", pluginPaths));

        CheckBox parallel = new CheckBox("Transform independent classes in parallel");
        parallel.setSelected(config.getPerformance().isParallel());
        Spinner<Integer> workers = new Spinner<>(0, 64, config.getPerformance().getParallelism());
        workers.setEditable(true);
        workers.disableProperty().bind(parallel.selectedProperty().not());
        Spinner<Integer> minimumClasses = new Spinner<>(1, 100_000,
                Math.max(1, config.getPerformance().getMinimumClasses()));
        minimumClasses.setEditable(true);
        minimumClasses.disableProperty().bind(parallel.selectedProperty().not());
        Label workerHint = Ui.label(
                "Use 0 workers to match the machine automatically. Small JARs stay sequential to avoid scheduling overhead.",
                "setting-description");
        workerHint.setWrapText(true);
        VBox performance = Ui.section("Build performance",
                "Parallel workers accelerate class-local transforms while coordinated rename and analysis phases remain deterministic.",
                parallel,
                Ui.fieldRow("Worker threads", workers),
                Ui.fieldRow("Parallel threshold", minimumClasses),
                workerHint);

        content.getChildren().addAll(
                Ui.pageHeader("Resources", "Control naming, mappings, rules, and resource-safe behavior without editing YAML."),
                naming, performance, rules, plugins);

        dictionary.valueProperty().addListener((obs, old, value) -> { config.setDictionary(value); touch(); });
        packageMode.valueProperty().addListener((obs, old, value) -> { config.setPackageMode(value); touch(); });
        flatten.textProperty().addListener((obs, old, value) -> { config.setFlattenPackage(value.trim()); touch(); });
        mapping.selectedProperty().addListener((obs, old, value) -> { config.getMapping().setEnabled(value); touch(); });
        mappingFormat.valueProperty().addListener((obs, old, value) -> {
            MappingFormat format = MappingFormat.parse(value);
            String output = mappingOutput.getText().trim();
            if (output.isBlank() || Arrays.stream(MappingFormat.values())
                    .map(MappingFormat::defaultFileName).anyMatch(output::equalsIgnoreCase)) {
                mappingOutput.setText(format.defaultFileName());
            }
            config.getMapping().setFormat(format.id());
            touch();
        });
        mappingOutput.textProperty().addListener((obs, old, value) -> { config.getMapping().setOutput(value.trim()); touch(); });
        encryptMapping.selectedProperty().addListener((obs, old, value) -> {
            config.getMapping().setEncrypted(value);
            String output = mappingOutput.getText().trim();
            if (value && !output.toLowerCase(Locale.ROOT).endsWith(".enc")) {
                mappingOutput.setText(output.isBlank() ? "mapping.txt.enc" : output + ".enc");
            } else if (!value && output.toLowerCase(Locale.ROOT).endsWith(".enc")) {
                mappingOutput.setText(output.substring(0, output.length() - 4));
            }
            touch();
        });
        mappingPassword.textProperty().addListener((obs, old, value) -> {
            char[] password = value.toCharArray();
            try {
                config.getMapping().setPassword(password);
            } finally {
                Arrays.fill(password, '\0');
            }
            touch();
        });
        passwordEnvironment.textProperty().addListener((obs, old, value) -> {
            config.getMapping().setPasswordEnvironment(value);
            touch();
        });
        parallel.selectedProperty().addListener((obs, old, value) -> {
            config.getPerformance().setParallel(value);
            touch();
        });
        workers.valueProperty().addListener((obs, old, value) -> {
            config.getPerformance().setParallelism(value);
            touch();
        });
        minimumClasses.valueProperty().addListener((obs, old, value) -> {
            config.getPerformance().setMinimumClasses(value);
            touch();
        });
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
