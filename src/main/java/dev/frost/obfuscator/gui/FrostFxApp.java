package dev.frost.obfuscator.gui;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ConfigWriter;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.engine.ObfuscationEngine;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.TransformerRegistry;
import dev.frost.obfuscator.util.Logger;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class FrostFxApp extends Application {

    private static final Map<String, TransformerMeta> META = Map.ofEntries(
            meta("class-rename", "Class Rename", "Renaming", "Renames classes with package-aware naming."),
            meta("field-rename", "Field Rename", "Renaming", "Renames fields and preserves configured names."),
            meta("method-rename", "Method Rename", "Renaming", "Hierarchy-aware method renaming."),
            meta("local-variable-rename", "Local Variables", "Cleanup", "Renames local variables and parameters."),
            meta("remove-debug", "Remove Debug", "Cleanup", "Strips debug tables and source metadata."),
            meta("string-encryption", "String Encryption", "Constants", "Encrypts string constants."),
            meta("number-obfuscation", "Number Obfuscation", "Constants", "Mutates numeric constants."),
            meta("parameter-encryption", "Parameter Encryption", "Calls", "Encodes eligible method parameters."),
            meta("flow-obfuscation", "Control Flow", "Flow", "Opaque predicates and flattening."),
            meta("flow-outliner", "Flow Outliner", "Flow", "Extracts safe blocks into private methods."),
            meta("flow-range", "Try Range Flow", "Flow", "Wraps code regions in synthetic handlers."),
            meta("flow-condition", "Condition Guards", "Flow", "Adds opaque guards around branches."),
            meta("flow-exception", "Exception Flow", "Flow", "Exception-driven control flow guards."),
            meta("flow-switch", "Switch Mutation", "Flow", "Hashes switch case values."),
            meta("stack-manipulation", "Stack Noise", "Bytecode", "Adds stack-neutral instruction noise."),
            meta("invoke-dynamic", "InvokeDynamic", "Calls", "Converts calls to dynamic call sites."),
            meta("reference-hiding", "Reference Hiding", "Calls", "Routes calls through generated proxies."),
            meta("access-modifier", "Access Noise", "Metadata", "Adds safe access metadata noise."),
            meta("metadata-noise", "Metadata Noise", "Metadata", "Adds bounded metadata noise.")
    );

    private static final Map<String, List<OptionSpec>> OPTION_SPECS = buildOptionSpecs();

    private Stage stage;
    private ObfuscationConfig config;
    private boolean applyingPreset;
    private boolean refreshingControls;
    private String currentPage = "project";
    private String activePreset = "balanced";
    private String selectedTransformer;
    private Node projectNode;
    private Node transformersNode;
    private Node consoleNode;

    private final StackPane contentHost = new StackPane();
    private final Label pageTitle = new Label("Project");
    private final Label pageSubtitle = new Label("Input, output, mappings, and launch profile.");
    private final Label statusLabel = new Label("Ready");
    private final Label topPresetLabel = new Label("Balanced");
    private final Label topEnabledLabel = new Label("0 / 0 passes");
    private final ProgressBar runProgress = new ProgressBar(0);
    private final Label toast = new Label();

    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final Map<String, Button> presetButtons = new LinkedHashMap<>();

    private final TextField inputField = textField("Select input jar");
    private final TextField outputField = textField("Choose output jar");
    private final TextField libsField = textField("Optional library folder");
    private final TextField flattenPackageField = textField("obf");
    private final TextField mappingOutputField = textField("mapping.txt");
    private final TextArea inclusionsArea = textArea("One regex per line");
    private final TextArea exclusionsArea = textArea("One regex per line");
    private final ComboBox<String> dictionaryBox = comboBox("alphabet", "unicode", "numeric");
    private final ComboBox<String> packageModeBox = comboBox("keep", "flatten", "remove");
    private final CheckBox mappingEnabledBox = checkBox("Export mapping file");

    private final ObservableList<String> transformerItems = FXCollections.observableArrayList();
    private final ListView<String> transformerList = new ListView<>(transformerItems);
    private final TextField transformerSearch = textField("Search transformers");
    private final CheckBox selectedEnabledBox = checkBox("Enabled");
    private final Label selectedTitle = new Label("Select a transformer");
    private final Label selectedSummary = new Label("Settings appear here.");
    private final Label selectedCategory = new Label("Transformer");
    private final VBox settingsBox = new VBox(14);
    private final TextArea logArea = textArea("");

    private Label sidebarPresetName;
    private Label sidebarPresetStats;
    private ProgressBar presetStrengthBar;

    public static void launchApp(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        config = ConfigLoader.loadDefault();
        ensureAllTransformers(config);

        HBox shell = new HBox();
        shell.getStyleClass().add("app-root");
        VBox sidebar = sidebar();
        VBox main = mainArea();
        HBox.setHgrow(main, Priority.ALWAYS);
        shell.getChildren().addAll(sidebar, main);

        StackPane root = new StackPane(shell, toast);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 26, 0));
        toast.getStyleClass().add("toast");
        toast.setManaged(false);
        toast.setVisible(false);

        Scene scene = new Scene(root, 1360, 850);
        String css = getClass().getResource("/frost-gui.css").toExternalForm();
        scene.getStylesheets().add(css);

        primaryStage.setTitle("Frostfuscator");
        primaryStage.setMinWidth(1180);
        primaryStage.setMinHeight(760);
        primaryStage.setScene(scene);

        bindProjectControls();
        loadConfigIntoUi(config);
        showPage("project", false);
        updatePresetVisuals();
        primaryStage.show();
    }

    private VBox sidebar() {
        VBox side = new VBox(18);
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(278);
        side.setMinWidth(278);
        side.setMaxWidth(278);

        HBox brand = new HBox(12);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("F");
        mark.getStyleClass().add("brand-mark");
        VBox brandText = new VBox(2);
        Label name = new Label("Frostfuscator");
        name.getStyleClass().add("brand-title");
        Label subtitle = new Label("Java Bytecode Obfuscation.");
        subtitle.getStyleClass().add("muted-label");
        brandText.getChildren().addAll(name, subtitle);
        brand.getChildren().addAll(mark, brandText);

        VBox nav = new VBox(8);
        nav.getChildren().addAll(
                navButton("Project", "Input, output, mapping", "project"),
                navButton("Transformers", "Per-pass settings", "transformers"),
                navButton("Console", "Live run output", "console")
        );

        Label presetTitle = sectionTitle("Preset");
        VBox presets = new VBox(8);
        presets.getChildren().addAll(
                presetButton("Compatibility", "compatibility"),
                presetButton("Balanced", "balanced"),
                presetButton("Strong", "strong"),
                presetButton("Maximum", "maximum")
        );

        VBox profileCard = new VBox(10);
        profileCard.getStyleClass().add("profile-card");
        Label active = new Label("Active profile");
        active.getStyleClass().add("micro-label");
        sidebarPresetName = new Label("Balanced");
        sidebarPresetName.getStyleClass().add("profile-name");
        sidebarPresetStats = new Label("0 / 0 passes enabled");
        sidebarPresetStats.getStyleClass().add("muted-label");
        presetStrengthBar = new ProgressBar(0.5);
        presetStrengthBar.getStyleClass().add("strength-bar");
        profileCard.getChildren().addAll(active, sidebarPresetName, sidebarPresetStats, presetStrengthBar);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Button load = secondaryButton("Load Config");
        load.setOnAction(e -> loadConfigFile());
        Button save = secondaryButton("Save Config");
        save.setOnAction(e -> saveConfigFile());
        VBox configButtons = new VBox(8, load, save);

        side.getChildren().addAll(brand, nav, presetTitle, presets, profileCard, spacer, configButtons);
        return side;
    }

    private VBox mainArea() {
        VBox main = new VBox(18);
        main.getStyleClass().add("main");
        HBox top = topBar();
        VBox.setVgrow(contentHost, Priority.ALWAYS);
        contentHost.getStyleClass().add("content-host");
        main.getChildren().addAll(top, contentHost);
        return main;
    }

    private HBox topBar() {
        HBox top = new HBox(18);
        top.getStyleClass().add("top-bar");
        top.setAlignment(Pos.CENTER_LEFT);

        VBox titleBlock = new VBox(4);
        pageTitle.getStyleClass().add("page-title");
        pageSubtitle.getStyleClass().add("page-subtitle");
        titleBlock.getChildren().addAll(pageTitle, pageSubtitle);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        VBox metrics = new VBox(8);
        metrics.setAlignment(Pos.CENTER_RIGHT);
        HBox chips = new HBox(8);
        chips.setAlignment(Pos.CENTER_RIGHT);
        topPresetLabel.getStyleClass().add("metric-chip");
        topEnabledLabel.getStyleClass().add("metric-chip");
        chips.getChildren().addAll(topPresetLabel, topEnabledLabel);
        runProgress.getStyleClass().add("run-progress");
        runProgress.setPrefWidth(250);
        statusLabel.getStyleClass().add("status-label");
        HBox status = new HBox(10, runProgress, statusLabel);
        status.setAlignment(Pos.CENTER_RIGHT);
        metrics.getChildren().addAll(chips, status);

        Button validate = secondaryButton("Validate");
        validate.setOnAction(e -> validateConfiguration());
        Button reveal = secondaryButton("Reveal Output");
        reveal.setOnAction(e -> revealOutput());
        Button run = primaryButton("Run Obfuscation");
        run.setOnAction(e -> runObfuscation(run, validate));
        HBox actions = new HBox(8, validate, reveal, run);
        actions.setAlignment(Pos.CENTER_RIGHT);

        top.getChildren().addAll(titleBlock, metrics, actions);
        return top;
    }

    private Node projectPage() {
        VBox page = new VBox(16);
        page.getStyleClass().add("page");

        GridPane cards = new GridPane();
        cards.getStyleClass().add("card-grid");
        cards.setHgap(16);
        cards.setVgap(16);
        cards.getColumnConstraints().addAll(percentColumn(36), percentColumn(25), percentColumn(39));
        cards.getRowConstraints().add(new RowConstraints());
        Node files = projectFilesCard();
        Node build = buildSettingsCard();
        Node mappings = mappingCard();
        growInGrid(files);
        growInGrid(build);
        growInGrid(mappings);
        cards.add(files, 0, 0);
        cards.add(build, 1, 0);
        cards.add(mappings, 2, 0);

        VBox rules = card("Rules", "Regex filters");
        GridPane ruleGrid = new GridPane();
        ruleGrid.setHgap(14);
        ruleGrid.getColumnConstraints().addAll(percentColumn(50), percentColumn(50));
        ruleGrid.add(labeledArea("Inclusions", inclusionsArea), 0, 0);
        ruleGrid.add(labeledArea("Exclusions", exclusionsArea), 1, 0);
        VBox.setVgrow(ruleGrid, Priority.ALWAYS);
        rules.getChildren().add(ruleGrid);

        page.getChildren().addAll(cards, rules);
        VBox.setVgrow(rules, Priority.ALWAYS);
        return page;
    }

    private VBox projectFilesCard() {
        VBox box = card("Project Files", "Jar targets");
        box.getChildren().addAll(
                fileRow("Input JAR", inputField, this::browseInputJar),
                fileRow("Output JAR", outputField, this::browseOutputJar),
                fileRow("Libraries", libsField, this::browseLibraries)
        );
        HBox actions = new HBox(8);
        actions.getStyleClass().add("inline-actions");
        Button defaults = secondaryButton("Use Defaults");
        defaults.setOnAction(e -> useDefaultPaths());
        Button clear = secondaryButton("Clear");
        clear.setOnAction(e -> clearProjectPaths());
        actions.getChildren().addAll(defaults, clear);
        box.getChildren().add(actions);
        return box;
    }

    private VBox buildSettingsCard() {
        VBox box = card("Build Settings", "Name generation");
        box.getChildren().addAll(
                labeledControl("Dictionary", dictionaryBox),
                labeledControl("Package mode", packageModeBox),
                labeledControl("Flatten package", flattenPackageField)
        );
        return box;
    }

    private VBox mappingCard() {
        VBox box = card("Mappings", "Recovery output");
        box.getChildren().addAll(
                mappingEnabledBox,
                fileRow("Mapping output", mappingOutputField, this::browseMappingOutput),
                mutedText("Mapping files make obfuscated stack traces recoverable.")
        );
        return box;
    }

    private Node transformersPage() {
        HBox page = new HBox(16);
        page.getStyleClass().add("page");

        VBox listCard = card("Transformer Stack", "Search, enable, and inspect passes");
        listCard.setPrefWidth(500);
        listCard.setMinWidth(430);
        listCard.getChildren().add(transformerToolbar());
        transformerList.getStyleClass().add("transformer-list");
        transformerList.setCellFactory(view -> new TransformerCell());
        VBox.setVgrow(transformerList, Priority.ALWAYS);
        listCard.getChildren().add(transformerList);

        VBox detailCard = card("Settings", "Typed controls");
        HBox detailHeader = new HBox(14);
        detailHeader.setAlignment(Pos.CENTER_LEFT);
        VBox detailText = new VBox(4);
        selectedCategory.getStyleClass().add("category-pill");
        selectedTitle.getStyleClass().add("detail-title");
        selectedSummary.getStyleClass().add("muted-label");
        detailText.getChildren().addAll(selectedCategory, selectedTitle, selectedSummary);
        HBox.setHgrow(detailText, Priority.ALWAYS);
        detailHeader.getChildren().addAll(detailText, selectedEnabledBox);

        ScrollPane settingsScroll = new ScrollPane(settingsBox);
        settingsScroll.getStyleClass().add("settings-scroll");
        settingsScroll.setFitToWidth(true);
        VBox.setVgrow(settingsScroll, Priority.ALWAYS);
        detailCard.getChildren().addAll(detailHeader, settingsScroll);

        HBox.setHgrow(detailCard, Priority.ALWAYS);
        page.getChildren().addAll(listCard, detailCard);
        return page;
    }

    private HBox transformerToolbar() {
        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Button allOff = secondaryButton("All Off");
        allOff.setOnAction(e -> setAllTransformers(false));
        Button allOn = secondaryButton("All On");
        allOn.setOnAction(e -> setAllTransformers(true));
        HBox.setHgrow(transformerSearch, Priority.ALWAYS);
        toolbar.getChildren().addAll(allOff, transformerSearch, allOn);
        return toolbar;
    }

    private Node consolePage() {
        VBox page = new VBox(16);
        page.getStyleClass().add("page");
        VBox console = card("Run Console", "Engine output");
        logArea.getStyleClass().add("console-area");
        logArea.setEditable(false);
        Button clear = secondaryButton("Clear");
        clear.setOnAction(e -> logArea.clear());
        HBox tools = new HBox(clear);
        tools.setAlignment(Pos.CENTER_RIGHT);
        console.getChildren().addAll(tools, logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        page.getChildren().add(console);
        VBox.setVgrow(console, Priority.ALWAYS);
        return page;
    }

    private void bindProjectControls() {
        ChangeListener<String> summaryListener = (obs, old, value) -> updateSummary();
        inputField.textProperty().addListener(summaryListener);
        outputField.textProperty().addListener(summaryListener);
        packageModeBox.valueProperty().addListener((obs, old, value) ->
                flattenPackageField.setDisable(!"flatten".equals(value)));
        transformerSearch.textProperty().addListener((obs, old, value) -> refreshTransformerList());
        transformerList.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (value != null && !value.equals(selectedTransformer)) {
                selectTransformer(value);
            }
        });
        selectedEnabledBox.selectedProperty().addListener((obs, old, value) -> {
            if (selectedTransformer == null || applyingPreset || refreshingControls) {
                return;
            }
            TransformerConfig tc = config.getTransformers().get(selectedTransformer);
            if (tc != null) {
                tc.setEnabled(value);
                markCustom();
                refreshTransformerList();
                updateStats();
            }
        });
    }

    private void loadConfigIntoUi(ObfuscationConfig cfg) {
        inputField.setText(nullToEmpty(cfg.getInput()));
        outputField.setText(nullToEmpty(cfg.getOutput()));
        libsField.setText(nullToEmpty(cfg.getLibs()));
        dictionaryBox.setValue(cfg.getDictionary());
        packageModeBox.setValue(cfg.getPackageMode());
        flattenPackageField.setText(nullToEmpty(cfg.getFlattenPackage()));
        flattenPackageField.setDisable(!"flatten".equals(cfg.getPackageMode()));
        mappingEnabledBox.setSelected(cfg.getMapping().isEnabled());
        mappingOutputField.setText(nullToEmpty(cfg.getMapping().getOutput()));
        inclusionsArea.setText(String.join("\n", cfg.getInclusions()));
        exclusionsArea.setText(String.join("\n", cfg.getExclusions()));
        refreshTransformerList();
        if (!transformerItems.isEmpty()) {
            transformerList.getSelectionModel().select(0);
            selectTransformer(transformerItems.get(0));
        }
        updateStats();
        updateSummary();
    }

    private ObfuscationConfig buildConfigFromUi() {
        ObfuscationConfig cfg = new ObfuscationConfig();
        cfg.setInput(inputField.getText().trim());
        cfg.setOutput(outputField.getText().trim());
        cfg.setLibs(libsField.getText().trim());
        cfg.setDictionary(dictionaryBox.getValue());
        cfg.setPackageMode(packageModeBox.getValue());
        cfg.setFlattenPackage(flattenPackageField.getText().trim());
        cfg.setInclusions(lines(inclusionsArea));
        cfg.setExclusions(lines(exclusionsArea));
        cfg.setTransformers(copyTransformers(config.getTransformers()));
        ObfuscationConfig.MappingConfig mapping = new ObfuscationConfig.MappingConfig();
        mapping.setEnabled(mappingEnabledBox.isSelected());
        mapping.setOutput(mappingOutputField.getText().trim());
        cfg.setMapping(mapping);
        return cfg;
    }

    private Map<String, TransformerConfig> copyTransformers(Map<String, TransformerConfig> source) {
        Map<String, TransformerConfig> copy = new LinkedHashMap<>();
        for (String name : TransformerRegistry.getAllNames()) {
            TransformerConfig src = source.getOrDefault(name, new TransformerConfig());
            TransformerConfig dst = new TransformerConfig();
            dst.setEnabled(src.isEnabled());
            dst.setDictionary(src.getDictionary());
            dst.setExclusions(new ArrayList<>(src.getExclusions()));
            dst.setInclusions(new ArrayList<>(src.getInclusions()));
            dst.setOptions(new LinkedHashMap<>(src.getOptions()));
            copy.put(name, dst);
        }
        return copy;
    }

    private void showPage(String page, boolean animate) {
        currentPage = page;
        Node node;
        switch (page) {
            case "transformers" -> {
                pageTitle.setText("Transformers");
                pageSubtitle.setText("Per-pass controls, no raw option guessing.");
                if (transformersNode == null) {
                    transformersNode = transformersPage();
                }
                node = transformersNode;
            }
            case "console" -> {
                pageTitle.setText("Console");
                pageSubtitle.setText("Live obfuscation output and status.");
                if (consoleNode == null) {
                    consoleNode = consolePage();
                }
                node = consoleNode;
            }
            default -> {
                pageTitle.setText("Project");
                pageSubtitle.setText("Input, output, mappings, and launch profile.");
                if (projectNode == null) {
                    projectNode = projectPage();
                }
                node = projectNode;
            }
        }
        contentHost.getChildren().setAll(node);
        navButtons.forEach((name, button) -> setActive(button, name.equals(currentPage)));
        if (animate) {
            animateIn(node);
        }
    }

    private void animateIn(Node node) {
        node.setOpacity(0);
        node.setTranslateY(12);
        FadeTransition fade = new FadeTransition(Duration.millis(180), node);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(220), node);
        slide.setToY(0);
        new ParallelTransition(fade, slide).play();
    }

    private void refreshTransformerList() {
        String filter = transformerSearch.getText() == null ? "" : transformerSearch.getText().trim().toLowerCase(Locale.ROOT);
        String previous = selectedTransformer;
        transformerItems.setAll(TransformerRegistry.getAllNames().stream()
                .filter(name -> {
                    TransformerMeta meta = metaFor(name);
                    String haystack = (name + " " + meta.title + " " + meta.category + " " + meta.summary).toLowerCase(Locale.ROOT);
                    return filter.isEmpty() || haystack.contains(filter);
                })
                .toList());
        transformerList.refresh();
        if (previous != null && transformerItems.contains(previous)) {
            transformerList.getSelectionModel().select(previous);
        }
    }

    private void selectTransformer(String name) {
        selectedTransformer = name;
        TransformerConfig tc = config.getTransformers().get(name);
        TransformerMeta meta = metaFor(name);
        selectedCategory.setText(meta.category);
        selectedTitle.setText(meta.title);
        selectedSummary.setText(meta.summary);
        refreshingControls = true;
        selectedEnabledBox.setSelected(tc != null && tc.isEnabled());
        refreshingControls = false;
        buildTransformerSettings(name);
        transformerList.refresh();
    }

    private void buildTransformerSettings(String name) {
        settingsBox.getChildren().clear();
        TransformerConfig tc = config.getTransformers().get(name);
        if (tc == null) {
            settingsBox.getChildren().add(emptySettings("Transformer config is missing."));
            return;
        }

        List<OptionSpec> specs = OPTION_SPECS.getOrDefault(name, List.of());
        if (specs.isEmpty()) {
            settingsBox.getChildren().add(emptySettings("This pass only needs the enabled checkbox."));
            return;
        }

        for (OptionSpec spec : specs) {
            settingsBox.getChildren().add(settingControl(tc, spec));
        }
    }

    private Node settingControl(TransformerConfig tc, OptionSpec spec) {
        VBox row = new VBox(8);
        row.getStyleClass().add("setting-row");
        Label label = new Label(spec.label);
        label.getStyleClass().add("setting-title");
        Label help = new Label(spec.help);
        help.getStyleClass().add("setting-help");
        help.setWrapText(true);

        Node control = switch (spec.type) {
            case BOOLEAN -> booleanSetting(tc, spec);
            case CHOICE -> choiceSetting(tc, spec);
            case INTEGER -> integerSetting(tc, spec);
        };
        row.getChildren().addAll(label, control, help);
        return row;
    }

    private Node booleanSetting(TransformerConfig tc, OptionSpec spec) {
        CheckBox box = checkBox(spec.label);
        box.getStyleClass().add("setting-checkbox");
        box.setText("Enabled");
        box.setSelected(booleanValue(tc.getOptions().get(spec.key), (Boolean) spec.defaultValue));
        box.selectedProperty().addListener((obs, old, value) -> {
            tc.getOptions().put(spec.key, value);
            markCustom();
        });
        return box;
    }

    private Node choiceSetting(TransformerConfig tc, OptionSpec spec) {
        FlowPane choices = new FlowPane(8, 8);
        choices.getStyleClass().add("segmented");
        ToggleGroup group = new ToggleGroup();
        String current = stringValue(tc.getOptions().get(spec.key), String.valueOf(spec.defaultValue));
        Toggle fallback = null;
        for (String option : spec.choices) {
            ToggleButton button = new ToggleButton(pretty(option));
            button.getStyleClass().add("segmented-toggle");
            button.setUserData(option);
            button.setToggleGroup(group);
            button.setSelected(option.equalsIgnoreCase(current));
            if (option.equalsIgnoreCase(String.valueOf(spec.defaultValue)) || fallback == null) {
                fallback = button;
            }
            choices.getChildren().add(button);
        }
        if (group.getSelectedToggle() == null && fallback != null) {
            fallback.setSelected(true);
            tc.getOptions().put(spec.key, fallback.getUserData().toString());
        }
        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                if (old != null) {
                    Platform.runLater(() -> old.setSelected(true));
                }
                return;
            }
            tc.getOptions().put(spec.key, selected.getUserData().toString());
            markCustom();
        });
        return choices;
    }

    private Node integerSetting(TransformerConfig tc, OptionSpec spec) {
        int value = intValue(tc.getOptions().get(spec.key), (Integer) spec.defaultValue);
        value = clamp(value, spec.min, spec.max);

        Slider slider = new Slider(spec.min, spec.max, value);
        slider.getStyleClass().add("setting-slider");
        slider.setBlockIncrement(spec.step);
        slider.setMajorTickUnit(Math.max(spec.step, (spec.max - spec.min) / 4.0));
        Spinner<Integer> spinner = new Spinner<>();
        spinner.getStyleClass().add("setting-spinner");
        spinner.setEditable(true);
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(spec.min, spec.max, value, spec.step));
        spinner.setPrefWidth(106);

        ChangeListener<Number> sliderListener = (obs, old, number) -> {
            int rounded = roundToStep(number.intValue(), spec.step);
            rounded = clamp(rounded, spec.min, spec.max);
            if (!spinner.getValue().equals(rounded)) {
                spinner.getValueFactory().setValue(rounded);
            }
            tc.getOptions().put(spec.key, rounded);
            markCustom();
        };
        slider.valueProperty().addListener(sliderListener);
        spinner.valueProperty().addListener((obs, old, number) -> {
            if (number == null) {
                return;
            }
            int rounded = clamp(number, spec.min, spec.max);
            if ((int) slider.getValue() != rounded) {
                slider.setValue(rounded);
            }
            tc.getOptions().put(spec.key, rounded);
            markCustom();
        });

        HBox line = new HBox(12, slider, spinner);
        line.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        return line;
    }

    private Node emptySettings(String text) {
        VBox empty = new VBox(8);
        empty.getStyleClass().add("empty-settings");
        Label title = new Label("No extra controls");
        title.getStyleClass().add("setting-title");
        Label body = new Label(text);
        body.getStyleClass().add("muted-label");
        empty.getChildren().addAll(title, body);
        return empty;
    }

    private void applyPreset(String preset) {
        applyingPreset = true;
        activePreset = preset;
        switch (preset) {
            case "compatibility" -> applyCompatibilityPreset();
            case "strong" -> applyStrongPreset();
            case "maximum" -> applyMaximumPreset();
            default -> applyBalancedPreset();
        }
        applyingPreset = false;
        config = buildConfigFromUi();
        ensureAllTransformers(config);
        updatePresetVisuals();
        refreshTransformerList();
        if (selectedTransformer != null) {
            selectTransformer(selectedTransformer);
        }
        updateStats();
        showToast(capitalize(preset) + " preset applied");
        pulse(sidebarPresetName);
    }

    private void applyCompatibilityPreset() {
        dictionaryBox.setValue("alphabet");
        packageModeBox.setValue("keep");
        flattenPackageField.setText("obf");
        setPreset("class-rename", true, options("mode", "safe"));
        setPreset("field-rename", true, options("mode", "safe"));
        setPreset("method-rename", true, options("mode", "safe"));
        setPreset("local-variable-rename", true, options());
        setPreset("remove-debug", true, options("remove-source-file", true, "remove-line-numbers", true, "remove-local-variables", true, "remove-parameters", true));
        setPreset("string-encryption", true, options("mode", "lite", "min-length", 2, "max-method-instructions", 6000));
        setPreset("number-obfuscation", true, options("probability", 45, "max-per-method", 32, "max-per-class", 96, "max-method-instructions", 6000));
        setPreset("parameter-encryption", false, options("probability", 20));
        setPreset("flow-obfuscation", true, options("mode", "lite", "exception-guards", false, "stack-noise", false, "flatten", false, "predicate-rate", 4, "max-predicates-per-method", 8, "max-method-instructions", 5000));
        setPreset("flow-outliner", false, options("probability", 10, "max-per-class", 8));
        setPreset("flow-range", false, options("probability", 15));
        setPreset("flow-condition", false, options("probability", 10, "max-per-method", 8));
        setPreset("flow-exception", false, options("strength", "WEAK"));
        setPreset("flow-switch", true, options("probability", 35));
        setPreset("stack-manipulation", false, options("probability", 5, "max-per-method", 8));
        setPreset("invoke-dynamic", true, options("probability", 15, "mutable-callsites", false));
        setPreset("reference-hiding", true, options("probability", 20, "max-per-class", 40, "max-method-instructions", 6000));
        setPreset("access-modifier", true, options("synthetic", true, "bridge-methods", false, "relax-final", false));
        setPreset("metadata-noise", false, options("strings-per-class", 4, "deprecated", false, "signatures", false));
    }

    private void applyBalancedPreset() {
        dictionaryBox.setValue("alphabet");
        packageModeBox.setValue("keep");
        flattenPackageField.setText("obf");
        setPreset("class-rename", true, options("mode", "aggressive"));
        setPreset("field-rename", true, options("mode", "aggressive"));
        setPreset("method-rename", true, options("mode", "aggressive"));
        setPreset("local-variable-rename", true, options());
        setPreset("remove-debug", true, options("remove-source-file", true, "remove-line-numbers", true, "remove-local-variables", true, "remove-parameters", true));
        setPreset("string-encryption", true, options("mode", "heavy", "min-length", 1, "max-method-instructions", 6000));
        setPreset("number-obfuscation", true, options("probability", 80, "max-per-method", 96, "max-per-class", 256, "max-method-instructions", 6000));
        setPreset("parameter-encryption", true, options("probability", 30));
        setPreset("flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 8, "max-predicates-per-method", 24, "max-method-instructions", 5000));
        setPreset("flow-outliner", true, options("probability", 25, "max-per-class", 16));
        setPreset("flow-range", true, options("probability", 35));
        setPreset("flow-condition", true, options("probability", 25, "max-per-method", 16));
        setPreset("flow-exception", true, options("strength", "GOOD"));
        setPreset("flow-switch", true, options("probability", 75));
        setPreset("stack-manipulation", true, options("probability", 8, "max-per-method", 16));
        setPreset("invoke-dynamic", true, options("probability", 35, "mutable-callsites", true));
        setPreset("reference-hiding", true, options("probability", 45, "max-per-class", 96, "max-method-instructions", 6000));
        setPreset("access-modifier", true, options("synthetic", true, "bridge-methods", false, "relax-final", false));
        setPreset("metadata-noise", true, options("strings-per-class", 8, "deprecated", true, "signatures", true));
    }

    private void applyStrongPreset() {
        applyBalancedPreset();
        setPreset("string-encryption", true, options("mode", "condy", "min-length", 1, "max-method-instructions", 6000));
        setPreset("number-obfuscation", true, options("probability", 90, "max-per-method", 128, "max-per-class", 320, "max-method-instructions", 6000));
        setPreset("parameter-encryption", true, options("probability", 45));
        setPreset("flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 12, "max-predicates-per-method", 32, "max-method-instructions", 5000));
        setPreset("flow-outliner", true, options("probability", 35, "max-per-class", 22));
        setPreset("flow-range", true, options("probability", 48));
        setPreset("flow-condition", true, options("probability", 38, "max-per-method", 24));
        setPreset("flow-exception", true, options("strength", "AGGRESSIVE"));
        setPreset("stack-manipulation", true, options("probability", 11, "max-per-method", 22));
        setPreset("invoke-dynamic", true, options("probability", 55, "mutable-callsites", true));
        setPreset("reference-hiding", true, options("probability", 65, "max-per-class", 144, "max-method-instructions", 6000));
        setPreset("metadata-noise", true, options("strings-per-class", 12, "deprecated", true, "signatures", true));
    }

    private void applyMaximumPreset() {
        applyStrongPreset();
        setPreset("string-encryption", true, options("mode", "polymorphic", "min-length", 1, "max-method-instructions", 6000));
        setPreset("number-obfuscation", true, options("probability", 100, "max-per-method", 160, "max-per-class", 512, "max-method-instructions", 6000));
        setPreset("parameter-encryption", true, options("probability", 60));
        setPreset("flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 16, "max-predicates-per-method", 40, "max-method-instructions", 5000));
        setPreset("flow-outliner", true, options("probability", 45, "max-per-class", 28));
        setPreset("flow-range", true, options("probability", 60));
        setPreset("flow-condition", true, options("probability", 50, "max-per-method", 32));
        setPreset("flow-switch", true, options("probability", 90));
        setPreset("stack-manipulation", true, options("probability", 14, "max-per-method", 28));
        setPreset("invoke-dynamic", true, options("probability", 75, "mutable-callsites", true));
        setPreset("reference-hiding", true, options("probability", 80, "max-per-class", 192, "max-method-instructions", 6000));
        setPreset("metadata-noise", true, options("strings-per-class", 18, "deprecated", true, "signatures", true));
    }

    private void setPreset(String name, boolean enabled, Map<String, Object> options) {
        TransformerConfig tc = config.getTransformers().computeIfAbsent(name, key -> new TransformerConfig());
        tc.setEnabled(enabled);
        tc.setDictionary(dictionaryBox.getValue());
        tc.setOptions(new LinkedHashMap<>(options));
    }

    private Map<String, Object> options(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private void setAllTransformers(boolean enabled) {
        for (TransformerConfig tc : config.getTransformers().values()) {
            tc.setEnabled(enabled);
        }
        markCustom();
        refreshTransformerList();
        if (selectedTransformer != null) {
            selectTransformer(selectedTransformer);
        }
        updateStats();
    }

    private void runObfuscation(Button runButton, Button validateButton) {
        ObfuscationConfig runConfig = buildConfigFromUi();
        try {
            ConfigLoader.validate(runConfig);
        } catch (Exception ex) {
            showError(ex);
            return;
        }

        config = runConfig;
        showPage("console", true);
        logArea.clear();
        setRunning(true, runButton, validateButton);
        Consumer<String> listener = line -> Platform.runLater(() -> {
            logArea.appendText(line + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
        Logger.addListener(listener);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                new ObfuscationEngine(runConfig, null).run();
                return null;
            }

            @Override
            protected void succeeded() {
                Logger.removeListener(listener);
                setRunning(false, runButton, validateButton);
                statusLabel.setText("Completed");
                logArea.appendText("\nDone. Output: " + runConfig.getOutput() + "\n");
                showToast("Obfuscation complete");
            }

            @Override
            protected void failed() {
                Logger.removeListener(listener);
                setRunning(false, runButton, validateButton);
                statusLabel.setText("Failed");
                Throwable ex = getException();
                showError(ex == null ? new RuntimeException("Run failed") : ex);
            }
        };
        Thread thread = new Thread(task, "frostfuscator-gui-runner");
        thread.setDaemon(true);
        thread.start();
    }

    private void setRunning(boolean running, Button runButton, Button validateButton) {
        runButton.setDisable(running);
        validateButton.setDisable(running);
        runProgress.setProgress(running ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        statusLabel.setText(running ? "Running" : "Ready");
    }

    private void validateConfiguration() {
        try {
            config = buildConfigFromUi();
            ConfigLoader.validate(config);
            statusLabel.setText("Valid");
            showToast("Configuration is valid");
        } catch (Exception ex) {
            statusLabel.setText("Needs input");
            showError(ex);
        }
    }

    private void loadConfigFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load config");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML config", "*.yml", "*.yaml"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        try {
            config = ConfigLoader.load(file.toPath());
            ensureAllTransformers(config);
            activePreset = "custom";
            loadConfigIntoUi(config);
            updatePresetVisuals();
            showToast("Config loaded");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void saveConfigFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save config");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML config", "*.yml", "*.yaml"));
        chooser.setInitialFileName("config.yml");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            config = buildConfigFromUi();
            ConfigWriter.save(config, ensureExtension(file.toPath(), ".yml"));
            showToast("Config saved");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void browseInputJar() {
        FileChooser chooser = jarChooser("Select input JAR");
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        inputField.setText(file.getAbsolutePath());
        if (isDefaultOutput(outputField.getText())) {
            outputField.setText(suggestOutputPath(file.toPath()));
        }
    }

    private void browseOutputJar() {
        FileChooser chooser = jarChooser("Save output JAR");
        chooser.setInitialFileName("output.jar");
        File file = chooser.showSaveDialog(stage);
        if (file != null) {
            outputField.setText(ensureExtension(file.toPath(), ".jar").toString());
        }
    }

    private void browseMappingOutput() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save mapping file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
        chooser.setInitialFileName("mapping.txt");
        File file = chooser.showSaveDialog(stage);
        if (file != null) {
            mappingOutputField.setText(ensureExtension(file.toPath(), ".txt").toString());
        }
    }

    private void browseLibraries() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select libraries folder");
        File current = currentFile(libsField.getText());
        if (current != null && current.exists()) {
            chooser.setInitialDirectory(current.isDirectory() ? current : current.getParentFile());
        }
        File folder = chooser.showDialog(stage);
        if (folder != null) {
            libsField.setText(folder.getAbsolutePath());
        }
    }

    private FileChooser jarChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR files", "*.jar"));
        return chooser;
    }

    private void revealOutput() {
        String output = outputField.getText().trim();
        if (output.isBlank()) {
            showError(new IllegalStateException("Output path is empty."));
            return;
        }
        try {
            File target = Path.of(output).toAbsolutePath().toFile();
            File openTarget = target.exists() ? target : target.getParentFile();
            if (openTarget == null) {
                openTarget = Path.of(".").toAbsolutePath().toFile();
            }
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (target.exists() && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                    desktop.browseFileDirectory(target);
                } else {
                    desktop.open(openTarget);
                }
            }
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void useDefaultPaths() {
        if (inputField.getText().isBlank()) {
            inputField.setText("input.jar");
        }
        if (outputField.getText().isBlank()) {
            outputField.setText("output.jar");
        }
        if (mappingOutputField.getText().isBlank()) {
            mappingOutputField.setText("mapping.txt");
        }
    }

    private void clearProjectPaths() {
        inputField.clear();
        outputField.clear();
        libsField.clear();
    }

    private void markCustom() {
        if (applyingPreset) {
            return;
        }
        activePreset = "custom";
        updatePresetVisuals();
        updateStats();
    }

    private void updatePresetVisuals() {
        presetButtons.forEach((name, button) -> setActive(button, name.equals(activePreset)));
        String text = capitalize(activePreset);
        sidebarPresetName.setText(text);
        topPresetLabel.setText(text);
        double progress = switch (activePreset) {
            case "compatibility" -> 0.25;
            case "strong" -> 0.74;
            case "maximum" -> 1.0;
            case "custom" -> 0.55;
            default -> 0.52;
        };
        presetStrengthBar.setProgress(progress);
    }

    private void updateStats() {
        long enabled = config.getTransformers().values().stream().filter(TransformerConfig::isEnabled).count();
        int total = TransformerRegistry.getAllNames().size();
        String text = enabled + " / " + total + " passes enabled";
        sidebarPresetStats.setText(text);
        topEnabledLabel.setText(text);
    }

    private void updateSummary() {
        statusLabel.setText(shortPath("Input", inputField.getText()) + "  ->  " + shortPath("Output", outputField.getText()));
    }

    private String shortPath(String label, String value) {
        if (value == null || value.isBlank()) {
            return label + ": none";
        }
        return Path.of(value).getFileName().toString();
    }

    private Button navButton(String title, String subtitle, String page) {
        Button button = new Button(title + "\n" + subtitle);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(e -> showPage(page, true));
        navButtons.put(page, button);
        return button;
    }

    private Button presetButton(String title, String preset) {
        Button button = new Button(title);
        button.getStyleClass().add("preset-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> applyPreset(preset));
        presetButtons.put(preset, button);
        return button;
    }

    private Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("secondary-button");
        return button;
    }

    private VBox card(String title, String subtitle) {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("card-subtitle");
        VBox header = new VBox(3, titleLabel, subtitleLabel);
        card.getChildren().add(header);
        return card;
    }

    private Node fileRow(String label, TextField field, Runnable action) {
        VBox box = new VBox(7);
        Label labelNode = formLabel(label);
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        Button browse = secondaryButton("Browse");
        browse.setMinWidth(92);
        browse.setOnAction(e -> action.run());
        row.getChildren().addAll(field, browse);
        box.getChildren().addAll(labelNode, row);
        return box;
    }

    private Node labeledControl(String label, Node control) {
        VBox box = new VBox(7);
        box.getChildren().addAll(formLabel(label), control);
        return box;
    }

    private Node labeledArea(String label, TextArea area) {
        VBox box = new VBox(7);
        box.getChildren().addAll(formLabel(label), area);
        VBox.setVgrow(area, Priority.ALWAYS);
        return box;
    }

    private Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text.toUpperCase(Locale.ROOT));
        label.getStyleClass().add("section-title");
        return label;
    }

    private Label mutedText(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted-label");
        label.setWrapText(true);
        return label;
    }

    private TextField textField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("text-input");
        return field;
    }

    private TextArea textArea(String prompt) {
        TextArea area = new TextArea();
        area.setPromptText(prompt);
        area.getStyleClass().add("text-area");
        area.setWrapText(true);
        return area;
    }

    private ComboBox<String> comboBox(String... values) {
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(values));
        combo.getStyleClass().add("combo-box");
        combo.setMaxWidth(Double.MAX_VALUE);
        if (values.length > 0) {
            combo.setValue(values[0]);
        }
        return combo;
    }

    private CheckBox checkBox(String text) {
        CheckBox box = new CheckBox(text);
        box.getStyleClass().add("check-box");
        return box;
    }

    private ColumnConstraints percentColumn(double percent) {
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(percent);
        column.setFillWidth(true);
        return column;
    }

    private void growInGrid(Node node) {
        GridPane.setHgrow(node, Priority.ALWAYS);
        GridPane.setVgrow(node, Priority.ALWAYS);
        if (node instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
            region.setMaxHeight(Double.MAX_VALUE);
        }
    }

    private void setActive(Button button, boolean active) {
        if (active) {
            if (!button.getStyleClass().contains("active")) {
                button.getStyleClass().add("active");
            }
        } else {
            button.getStyleClass().remove("active");
        }
    }

    private void showToast(String message) {
        toast.setText(message);
        toast.setVisible(true);
        toast.setOpacity(0);
        FadeTransition in = new FadeTransition(Duration.millis(130), toast);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(Duration.millis(220), toast);
        out.setDelay(Duration.millis(1300));
        out.setToValue(0);
        out.setOnFinished(e -> toast.setVisible(false));
        in.setOnFinished(e -> out.play());
        in.play();
    }

    private void pulse(Node node) {
        ScaleTransition scale = new ScaleTransition(Duration.millis(180), node);
        scale.setFromX(1);
        scale.setFromY(1);
        scale.setToX(1.035);
        scale.setToY(1.035);
        scale.setAutoReverse(true);
        scale.setCycleCount(2);
        scale.play();
    }

    private void showError(Throwable throwable) {
        String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        showToast(message);
    }

    private void ensureAllTransformers(ObfuscationConfig cfg) {
        Map<String, TransformerConfig> map = cfg.getTransformers();
        for (String name : TransformerRegistry.getAllNames()) {
            map.computeIfAbsent(name, key -> {
                TransformerConfig tc = new TransformerConfig();
                tc.setDictionary(cfg.getDictionary());
                return tc;
            });
        }
    }

    private List<String> lines(TextArea area) {
        List<String> lines = new ArrayList<>();
        for (String line : area.getText().split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private Path ensureExtension(Path path, String extension) {
        String text = path.toString();
        return text.toLowerCase(Locale.ROOT).endsWith(extension) ? path : Path.of(text + extension);
    }

    private boolean isDefaultOutput(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String name = Path.of(value).getFileName().toString();
        return "output.jar".equalsIgnoreCase(name);
    }

    private String suggestOutputPath(Path input) {
        Path absolute = input.toAbsolutePath();
        String name = absolute.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        Path parent = absolute.getParent();
        Path output = (parent == null ? Path.of("") : parent).resolve(name + "-obf.jar");
        return output.toString();
    }

    private File currentFile(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value).toAbsolutePath().normalize().toFile();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String pretty(String value) {
        return value.replace('-', ' ').replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int roundToStep(int value, int step) {
        if (step <= 1) {
            return value;
        }
        return Math.round(value / (float) step) * step;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private TransformerMeta metaFor(String name) {
        return META.getOrDefault(name, new TransformerMeta(name, "Custom", name));
    }

    private static Map.Entry<String, TransformerMeta> meta(String key, String title, String category, String summary) {
        return Map.entry(key, new TransformerMeta(title, category, summary));
    }

    private static Map<String, List<OptionSpec>> buildOptionSpecs() {
        Map<String, List<OptionSpec>> specs = new LinkedHashMap<>();
        specs.put("class-rename", List.of(choice("mode", "Rename mode", "aggressive", List.of("safe", "aggressive"), "Safe keeps public and annotated classes stable.")));
        specs.put("field-rename", List.of(choice("mode", "Rename mode", "aggressive", List.of("safe", "aggressive"), "Safe keeps public/protected and annotated fields stable.")));
        specs.put("method-rename", List.of(choice("mode", "Rename mode", "aggressive", List.of("safe", "aggressive"), "Safe keeps public/protected and annotated methods stable.")));
        specs.put("remove-debug", List.of(
                bool("remove-source-file", "Source file", true, "Removes SourceFile metadata."),
                bool("remove-line-numbers", "Line numbers", true, "Removes LineNumberTable entries."),
                bool("remove-local-variables", "Local variables", true, "Removes LocalVariableTable entries."),
                bool("remove-parameters", "Parameters", true, "Removes method parameter metadata.")
        ));
        specs.put("string-encryption", List.of(
                choice("mode", "Encryption mode", "heavy", List.of("lite", "medium", "heavy", "condy", "polymorphic"), "Higher modes are stronger and heavier."),
                integer("min-length", "Minimum string length", 1, 0, 64, 1, "Strings shorter than this are ignored."),
                integer("max-method-instructions", "Method size cap", 6000, 500, 20000, 500, "Skips very large methods for verifier safety.")
        ));
        specs.put("number-obfuscation", List.of(
                integer("probability", "Mutation probability", 80, 0, 100, 1, "Percent chance for each eligible number."),
                integer("max-per-method", "Max per method", 96, 0, 512, 8, "Upper bound inside one method."),
                integer("max-per-class", "Max per class", 256, 0, 1500, 16, "Upper bound inside one class."),
                integer("max-method-instructions", "Method size cap", 6000, 500, 20000, 500, "Skips very large methods.")
        ));
        specs.put("parameter-encryption", List.of(integer("probability", "Encryption probability", 30, 0, 100, 1, "Percent chance for eligible private static methods.")));
        specs.put("flow-obfuscation", List.of(
                choice("mode", "Flow mode", "heavy", List.of("lite", "medium", "heavy"), "Controls CFG transformation intensity."),
                bool("exception-guards", "Exception guards", true, "Adds exception-backed opaque paths."),
                bool("stack-noise", "Stack noise", true, "Adds stack-neutral bytecode noise."),
                bool("flatten", "Flatten small methods", true, "Converts eligible methods into state machines."),
                integer("predicate-rate", "Predicate rate", 8, 0, 100, 1, "Percent chance for scattered predicates."),
                integer("max-predicates-per-method", "Max predicates", 24, 0, 120, 4, "Maximum injected predicates per method."),
                integer("max-method-instructions", "Method size cap", 5000, 500, 20000, 500, "Skips very large methods.")
        ));
        specs.put("flow-outliner", List.of(
                integer("probability", "Outline probability", 25, 0, 100, 1, "Percent chance for eligible methods."),
                integer("max-per-class", "Max per class", 16, 0, 128, 4, "Maximum extracted blocks per class.")
        ));
        specs.put("flow-range", List.of(integer("probability", "Range probability", 35, 0, 100, 1, "Percent chance for wrapping eligible methods.")));
        specs.put("flow-condition", List.of(
                integer("probability", "Guard probability", 25, 0, 100, 1, "Percent chance around eligible branches."),
                integer("max-per-method", "Max per method", 16, 0, 96, 4, "Maximum guards per method.")
        ));
        specs.put("flow-exception", List.of(choice("strength", "Strength", "GOOD", List.of("WEAK", "GOOD", "AGGRESSIVE"), "Aggressive runs more exception guard passes.")));
        specs.put("flow-switch", List.of(integer("probability", "Switch probability", 75, 0, 100, 1, "Percent chance for eligible switches.")));
        specs.put("stack-manipulation", List.of(
                integer("probability", "Noise probability", 8, 0, 100, 1, "Percent chance at safe anchors."),
                integer("max-per-method", "Max per method", 16, 0, 128, 4, "Maximum stack noise insertions.")
        ));
        specs.put("invoke-dynamic", List.of(
                integer("probability", "Callsite probability", 35, 0, 100, 1, "Percent chance for eligible calls."),
                bool("mutable-callsites", "Mutable callsites", true, "Uses MutableCallSite instead of ConstantCallSite.")
        ));
        specs.put("reference-hiding", List.of(
                integer("probability", "Proxy probability", 45, 0, 100, 1, "Percent chance for eligible calls."),
                integer("max-per-class", "Max per class", 96, 0, 512, 8, "Maximum generated proxies per class."),
                integer("max-method-instructions", "Method size cap", 6000, 500, 20000, 500, "Skips very large methods.")
        ));
        specs.put("access-modifier", List.of(
                bool("synthetic", "Synthetic flags", true, "Marks eligible members synthetic."),
                bool("bridge-methods", "Bridge methods", false, "Adds bridge flag to eligible methods."),
                bool("relax-final", "Relax final", false, "Removes final from eligible members.")
        ));
        specs.put("metadata-noise", List.of(
                integer("strings-per-class", "Strings per class", 8, 0, 64, 1, "Bounded metadata string noise."),
                bool("deprecated", "Deprecated markers", true, "Adds deprecated metadata."),
                bool("signatures", "Fake signatures", true, "Adds synthetic-looking signatures.")
        ));
        return specs;
    }

    private static OptionSpec bool(String key, String label, boolean defaultValue, String help) {
        return new OptionSpec(key, label, OptionType.BOOLEAN, defaultValue, 0, 0, 1, List.of(), help);
    }

    private static OptionSpec choice(String key, String label, String defaultValue, List<String> choices, String help) {
        return new OptionSpec(key, label, OptionType.CHOICE, defaultValue, 0, 0, 1, choices, help);
    }

    private static OptionSpec integer(String key, String label, int defaultValue, int min, int max, int step, String help) {
        return new OptionSpec(key, label, OptionType.INTEGER, defaultValue, min, max, step, List.of(), help);
    }

    private enum OptionType {
        BOOLEAN,
        CHOICE,
        INTEGER
    }

    private record TransformerMeta(String title, String category, String summary) {
    }

    private record OptionSpec(
            String key,
            String label,
            OptionType type,
            Object defaultValue,
            int min,
            int max,
            int step,
            List<String> choices,
            String help
    ) {
    }

    private final class TransformerCell extends ListCell<String> {
        private final CheckBox enabled = new CheckBox();
        private final Label title = new Label();
        private final Label summary = new Label();
        private final Label category = new Label();
        private final VBox text = new VBox(3, title, summary);
        private final Region spacer = new Region();
        private final HBox row = new HBox(12, enabled, text, spacer, category);

        private TransformerCell() {
            row.getStyleClass().add("transformer-row");
            row.setAlignment(Pos.CENTER_LEFT);
            title.getStyleClass().add("transformer-title");
            summary.getStyleClass().add("transformer-summary");
            category.getStyleClass().add("transformer-category");
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            enabled.setOnMouseClicked(e -> e.consume());
            enabled.setOnAction(e -> {
                String name = getItem();
                TransformerConfig tc = name == null ? null : config.getTransformers().get(name);
                if (tc != null) {
                    tc.setEnabled(enabled.isSelected());
                    markCustom();
                    updateStats();
                    if (name.equals(selectedTransformer)) {
                        selectedEnabledBox.setSelected(enabled.isSelected());
                    }
                    refreshTransformerList();
                }
            });
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && getItem() != null) {
                    transformerList.getSelectionModel().select(getItem());
                    selectTransformer(getItem());
                }
            });
        }

        @Override
        protected void updateItem(String name, boolean empty) {
            super.updateItem(name, empty);
            if (empty || name == null) {
                setGraphic(null);
                return;
            }
            TransformerMeta meta = metaFor(name);
            TransformerConfig tc = config.getTransformers().get(name);
            title.setText(meta.title);
            summary.setText(meta.summary);
            category.setText(meta.category);
            enabled.setSelected(tc != null && tc.isEnabled());
            row.getStyleClass().remove("selected");
            if (name.equals(selectedTransformer)) {
                row.getStyleClass().add("selected");
            }
            setGraphic(row);
        }
    }
}
