package dev.frost.obfuscator.gui;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ConfigWriter;
import dev.frost.obfuscator.config.FrostJNIConfig;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.engine.ObfuscationEngine;
import dev.frost.obfuscator.jni.compiler.CompilerDetector;
import dev.frost.obfuscator.jni.compiler.CompilerKind;
import dev.frost.obfuscator.jni.compiler.DetectedCompiler;
import dev.frost.obfuscator.jni.compiler.TargetPlatform;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.TransformerRegistry;
import dev.frost.obfuscator.util.Logger;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class FrostFxApp extends Application {

    private static final Map<String, TransformerMeta> META = Map.ofEntries(
            meta("class-rename", "Class Rename", "Renaming", "Renames classes with package-aware naming."),
            meta("field-rename", "Field Rename", "Renaming", "Renames fields, except configured names."),
            meta("method-rename", "Method Rename", "Renaming", "Hierarchy-aware method renaming."),
            meta("local-variable-rename", "Local Variables", "Cleanup", "Renames local variables and parameters."),
            meta("remove-debug", "Remove Debug", "Cleanup", "Strips debug tables and source metadata."),
            meta("string-encryption", "String Encryption", "Constants", "Encrypts string constants."),
            meta("number-obfuscation", "Number Obfuscation", "Constants", "Mutates numeric constants."),
            meta("parameter-encryption", "Parameter Encryption", "Calls", "Encodes eligible method parameters."),
            meta("flow-obfuscation", "Control Flow", "Flow", "Adds opaque predicates and flattening."),
            meta("flow-outliner", "Flow Outliner", "Flow", "Extracts safe blocks into private methods."),
            meta("flow-range", "Try Range Flow", "Flow", "Wraps code regions in synthetic handlers."),
            meta("flow-condition", "Condition Guards", "Flow", "Adds opaque guards around branches."),
            meta("flow-exception", "Exception Flow", "Flow", "Exception-driven control flow guards."),
            meta("flow-switch", "Switch Mutation", "Flow", "Hashes switch case values."),
            meta("stack-manipulation", "Stack Noise", "Bytecode", "Adds stack-neutral instruction noise."),
            meta("invoke-dynamic", "InvokeDynamic", "Calls", "Converts calls to dynamic call sites."),
            meta("reference-hiding", "Reference Hiding", "Calls", "Routes calls through generated proxies."),
            meta("access-modifier", "Access Noise", "Metadata", "Adds safe access metadata noise."),
            meta("metadata-noise", "Metadata Noise", "Metadata", "Adds bounded metadata noise."),
            meta("watermark", "Watermark", "Ownership", "Embeds ownership markers into classes and resources."),
            meta("integrity", "Integrity Index", "Protection", "Writes SHA-256 metadata for classes and resources."),
            meta("anti-debug", "Anti-Debug", "Protection", "Injects argument, agent, stack, timing, and optional process checks."),
            meta("anti-decompiler", "Anti-Decompiler", "Protection", "Adds verifier-safe bytecode traps aimed at decompilers."),
            meta("junk-code", "Junk Code", "Protection", "Adds bounded synthetic methods and fields."),
            meta("fake-classes", "Fake Classes", "Protection", "Generates verifier-safe decoy classes."),
            meta("inject-banner", "Inject Banner", "Funsies", "Injects custom text or ASCII banners into every class."),
            meta("emoji-hell", "Emoji Hell", "Funsies", "Injects emoji noise strings."),
            meta("copypasta-injector", "Copypasta", "Funsies", "Injects noisy joke/error strings."),
            meta("fake-application", "Fake Application", "Funsies", "Generates believable inert app-profile classes."),
            meta("chinese-mode", "Chinese Mode", "Funsies", "Renames and decorates classes with random Chinese text."),
            meta("resource-compression", "Resource Compression", "Resources", "Stores compressed resource copies and an index."),
            meta("resource-encryption", "Resource Encryption", "Resources", "Stores encrypted resource copies and an index."),
            meta("bytecode-optimizer", "Bytecode Optimizer", "Optimization", "Removes simple no-op bytecode."),
            meta("jar-shrinker", "JAR Shrinker", "Optimization", "Removes debug tables and source metadata."),
            meta("statistics-report", "Statistics Report", "Reporting", "Writes JSON or HTML run statistics.")
    );

    private static final Map<String, List<OptionSpec>> OPTION_SPECS = buildOptionSpecs();

    private Stage stage;
    private ObfuscationConfig config;
    private boolean applyingPreset;
    private boolean refreshingControls;
    private String currentPage = "project";
    private String activePreset = "none";
    private String currentCategory = "obfuscation";
    private String selectedTransformer;
    private Node projectNode;
    private Node consoleNode;
    private Stage rulesStage;

    private final StackPane contentHost = new StackPane();
    private final Label pageTitle = new Label("Project");
    private final Label pageSubtitle = new Label("Input, output, mappings, and launch profile.");
    private final Label statusLabel = new Label("Ready");
    private final Label topPresetLabel = new Label("No Passes");
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
    private final CheckBox nativeEnabledBox = checkBox("Enable FrostJNI native protection");
    private final TextField nativeLibraryField = textField("frostjni_protected");
    private final TextField nativeTempDirField = textField("Optional work directory");
    private final ComboBox<String> nativeModeBox = comboBox("SELECTIVE", "FULL");
    private final ComboBox<String> nativeCompileModeBox = comboBox("FAST", "RELEASE");
    private final ComboBox<String> nativeOptimizationBox = comboBox("O0", "O1", "O2", "O3");
    private final CheckBox nativeUseClangBox = checkBox("Use Clang");
    private final CheckBox nativeUseGccBox = checkBox("Use GCC / MinGW");
    private final CheckBox nativeUseMsvcBox = checkBox("Use MSVC Build Tools");
    private final CheckBox nativeStripSymbolsBox = checkBox("Strip symbols");
    private final CheckBox nativeUnityBuildBox = checkBox("Unity build");
    private final CheckBox nativeKeepSourcesBox = checkBox("Keep generated sources");
    private final CheckBox nativeDebugBox = checkBox("Debug mode");
    private final CheckBox nativeResourceEmbeddingBox = checkBox("Embed libraries in jar");
    private final CheckBox nativeFailFastBox = checkBox("Fail build on native errors");
    private final CheckBox nativeContinueBox = checkBox("Continue with Java output on failure");
    private final TextArea nativeIncludePackagesArea = textArea("com.example.security");
    private final TextArea nativeIncludeClassesArea = textArea("com.example.LicenseManager");
    private final TextArea nativeIncludeMethodsArea = textArea("com.example.Class#method");
    private final TextArea nativeIncludeAnnotationsArea = textArea("dev.frost.obfuscator.jni.loader.Native");
    private final TextArea nativeExcludePackagesArea = textArea("com.example.generated");
    private final TextArea nativeExcludeClassesArea = textArea("com.example.Main");
    private final TextArea nativeExcludeAnnotationsArea = textArea("javax.annotation.Generated");
    private final Label nativeCompilerStatusLabel = new Label("Compiler status not checked.");

    private final ObservableList<String> transformerItems = FXCollections.observableArrayList();
    private final ListView<String> transformerList = new ListView<>(transformerItems);
    private final TextField transformerSearch = textField("Search passes");
    private final CheckBox selectedEnabledBox = checkBox("Enabled");
    private final Label selectedTitle = new Label("Select a pass");
    private final Label selectedSummary = new Label("Settings appear here.");
    private final Label selectedCategory = new Label("Pass");
    private final VBox settingsBox = new VBox(14);
    private final TextArea logArea = textArea("");

    private ProgressBar presetStrengthBar;
    private double dragOffsetX;
    private double dragOffsetY;

    public static void launchApp(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        config = ConfigLoader.loadDefault();
        ensureAllTransformers(config);
        applyNoPassPreset();

        VBox shell = new VBox();
        shell.getStyleClass().addAll("app-root", "app-shell");
        VBox header = navigationBar();
        VBox main = mainArea();
        VBox.setVgrow(main, Priority.ALWAYS);
        shell.getChildren().addAll(header, main);
        shell.setOpacity(0);

        StackPane welcome = welcomeScreen();
        StackPane root = new StackPane(shell, welcome, toast);
        root.getStyleClass().add("window-frame");
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 26, 0));
        toast.getStyleClass().add("toast");
        toast.setManaged(false);
        toast.setVisible(false);

        Scene scene = new Scene(root, 1360, 850);
        scene.setFill(Color.TRANSPARENT);
        String css = getClass().getResource("/frost-gui.css").toExternalForm();
        scene.getStylesheets().add(css);

        primaryStage.setTitle("Frostfuscator");
        primaryStage.setMinWidth(1120);
        primaryStage.setMinHeight(720);
        primaryStage.setScene(scene);

        bindProjectControls();
        loadConfigIntoUi(config);
        showPage("project", false);
        updatePresetVisuals();
        primaryStage.show();
        playWelcome(welcome, shell);
    }

    private StackPane welcomeScreen() {
        StackPane screen = new StackPane();
        screen.getStyleClass().add("welcome-screen");

        Region gradientA = new Region();
        gradientA.getStyleClass().addAll("welcome-gradient", "welcome-gradient-a");
        Region gradientB = new Region();
        gradientB.getStyleClass().addAll("welcome-gradient", "welcome-gradient-b");

        VBox copy = new VBox(10);
        copy.setAlignment(Pos.CENTER);
        Label mark = new Label("\u2744");
        mark.getStyleClass().add("welcome-mark");
        Label title = new Label("Welcome to Frostfuscator");
        title.getStyleClass().add("welcome-title");
        Label subtitle = new Label("Java protection, obfuscation, and release hardening.");
        subtitle.getStyleClass().add("welcome-subtitle");
        copy.getChildren().addAll(mark, title, subtitle);

        screen.getChildren().addAll(gradientA, gradientB, copy);
        enableWindowDrag(screen);
        moveWelcomeGradient(gradientA, -520, 520, 3.8);
        moveWelcomeGradient(gradientB, 460, -460, 5.2);
        breathe(mark);
        return screen;
    }

    private void playWelcome(StackPane welcome, Node shell) {
        PauseTransition hold = new PauseTransition(Duration.millis(1250));
        hold.setOnFinished(e -> {
            FadeTransition out = new FadeTransition(Duration.millis(420), welcome);
            out.setToValue(0);
            FadeTransition in = new FadeTransition(Duration.millis(520), shell);
            in.setToValue(1);
            out.setOnFinished(done -> {
                welcome.setVisible(false);
                welcome.setManaged(false);
            });
            new ParallelTransition(out, in).play();
        });
        hold.play();
    }

    private void moveWelcomeGradient(Node node, double fromX, double toX, double seconds) {
        TranslateTransition motion = new TranslateTransition(Duration.seconds(seconds), node);
        motion.setFromX(fromX);
        motion.setToX(toX);
        motion.setAutoReverse(true);
        motion.setCycleCount(Animation.INDEFINITE);
        motion.play();
    }

    private Button titleButton(String styleClass) {
        Button button = new Button();
        button.getStyleClass().addAll("titlebar-button", styleClass);
        installHoverMotion(button);
        return button;
    }

    private VBox navigationBar() {
        VBox bar = new VBox(10);
        bar.getStyleClass().add("app-header");

        HBox topRow = new HBox(14);
        topRow.setAlignment(Pos.CENTER_LEFT);
        HBox brand = new HBox(12);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("\u2744");
        mark.getStyleClass().add("brand-mark");
        VBox brandText = new VBox(2);
        Label name = new Label("Frostfuscator");
        name.getStyleClass().add("brand-title");
        Label subtitle = new Label("Obfuscation toolkit");
        subtitle.getStyleClass().add("muted-label");
        brandText.getChildren().addAll(name, subtitle);
        brand.getChildren().addAll(mark, brandText);

        FlowPane nav = new FlowPane(8, 8);
        nav.getStyleClass().add("nav-row");
        nav.getChildren().addAll(
                navButton("Project", "", "project"),
                navButton("Obfuscation", "", "obfuscation"),
                navButton("Protection", "", "protection"),
                navButton("FrostJNI", "", "native-protection"),
                navButton("Resources", "", "resources"),
                navButton("Funsies", "", "funsies"),
                navButton("Optimize", "", "optimization"),
                navButton("Reports", "", "reporting"),
                navButton("Console", "", "console")
        );

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button load = secondaryButton("Load Config");
        load.setOnAction(e -> loadConfigFile());
        Button save = secondaryButton("Save Config");
        save.setOnAction(e -> saveConfigFile());
        HBox configButtons = new HBox(8, load, save);
        configButtons.setAlignment(Pos.CENTER_RIGHT);

        Button minimize = titleButton("title-minimize");
        minimize.setOnAction(e -> stage.setIconified(true));
        Button maximize = titleButton("title-maximize");
        maximize.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        Button close = titleButton("title-close");
        close.setOnAction(e -> stage.close());
        HBox windowButtons = new HBox(10, minimize, maximize, close);
        windowButtons.setAlignment(Pos.CENTER_RIGHT);

        topRow.getChildren().addAll(brand, spacer, windowButtons);

        Region navSpacer = new Region();
        HBox.setHgrow(navSpacer, Priority.ALWAYS);
        HBox navLine = new HBox(10, nav, navSpacer, configButtons);
        navLine.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nav, Priority.ALWAYS);

        StackPane motionHost = new StackPane();
        motionHost.getStyleClass().add("motion-host");
        Region motionLine = new Region();
        motionLine.getStyleClass().add("motion-line");
        motionHost.getChildren().add(motionLine);

        bar.getChildren().addAll(topRow, navLine, motionHost);
        enableWindowDrag(bar);
        startHeaderMotion(motionLine);
        breathe(mark);
        return bar;
    }

    private void startHeaderMotion(Node node) {
        TranslateTransition motion = new TranslateTransition(Duration.seconds(7), node);
        motion.setFromX(-760);
        motion.setToX(760);
        motion.setCycleCount(Animation.INDEFINITE);
        motion.play();
    }

    private void breathe(Node node) {
        FadeTransition fade = new FadeTransition(Duration.seconds(2.8), node);
        fade.setFromValue(0.72);
        fade.setToValue(1.0);
        fade.setAutoReverse(true);
        fade.setCycleCount(Animation.INDEFINITE);
        fade.play();
    }

    private void enableWindowDrag(Node node) {
        node.setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        node.setOnMouseDragged(e -> {
            if (!stage.isMaximized()) {
                stage.setX(e.getScreenX() - dragOffsetX);
                stage.setY(e.getScreenY() - dragOffsetY);
            }
        });
        node.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });
    }

    private VBox mainArea() {
        VBox main = new VBox(14);
        main.getStyleClass().add("main");
        HBox top = topBar();
        VBox.setVgrow(contentHost, Priority.ALWAYS);
        contentHost.setMinHeight(0);
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
        runProgress.setPrefWidth(180);
        statusLabel.getStyleClass().add("status-label");
        HBox status = new HBox(10, runProgress, statusLabel);
        status.setAlignment(Pos.CENTER_RIGHT);
        metrics.getChildren().addAll(chips, status);

        Button validate = secondaryButton("Validate");
        validate.setOnAction(e -> validateConfiguration());
        Button reveal = secondaryButton("Reveal Output");
        reveal.setOnAction(e -> revealOutput());
        Button run = primaryButton("Run Build");
        run.setOnAction(e -> runProtection(run, validate));
        HBox actions = new HBox(8, validate, reveal, run);
        actions.setAlignment(Pos.CENTER_RIGHT);

        top.getChildren().addAll(titleBlock, metrics, actions);
        return top;
    }

    private Node projectPage() {
        VBox page = new VBox(14);
        page.getStyleClass().add("page");

        GridPane cards = new GridPane();
        cards.getStyleClass().add("card-grid");
        cards.setHgap(14);
        cards.setVgap(14);
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

        page.getChildren().addAll(cards, profileCard());
        return page;
    }

    private VBox profileCard() {
        VBox box = card("Launch Profile", "Start with no passes, then opt into more");
        HBox presets = new HBox(8);
        presets.setAlignment(Pos.CENTER_LEFT);
        presets.getChildren().addAll(
                presetButton("No Passes", "none"),
                presetButton("Basic", "basic"),
                presetButton("Balanced", "balanced"),
                presetButton("Strong", "strong"),
                presetButton("Maximum", "maximum")
        );
        presetStrengthBar = new ProgressBar(0);
        presetStrengthBar.getStyleClass().add("strength-bar");
        presetStrengthBar.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().addAll(presets, presetStrengthBar);
        return box;
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
        Button rules = secondaryButton("Edit Rules");
        rules.setOnAction(e -> showRulesWindow());
        actions.getChildren().addAll(defaults, clear, rules);
        box.getChildren().add(actions);
        return box;
    }

    private void showRulesWindow() {
        if (rulesStage != null) {
            rulesStage.show();
            rulesStage.toFront();
            return;
        }

        rulesStage = new Stage(StageStyle.TRANSPARENT);
        rulesStage.initOwner(stage);
        rulesStage.initModality(Modality.NONE);
        rulesStage.setTitle("Rules");

        VBox root = new VBox(14);
        root.getStyleClass().addAll("app-root", "rules-window", "window-frame");

        HBox title = new HBox(12);
        title.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Rules");
        heading.getStyleClass().add("card-title");
        Label sub = new Label("Regex filters");
        sub.getStyleClass().add("muted-label");
        VBox text = new VBox(2, heading, sub);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = titleButton("title-close");
        close.setOnAction(e -> rulesStage.hide());
        title.getChildren().addAll(text, spacer, close);

        GridPane ruleGrid = new GridPane();
        ruleGrid.setHgap(12);
        ruleGrid.getColumnConstraints().addAll(percentColumn(50), percentColumn(50));
        ruleGrid.add(labeledArea("Inclusions", inclusionsArea), 0, 0);
        ruleGrid.add(labeledArea("Exclusions", exclusionsArea), 1, 0);

        Button done = primaryButton("Done");
        done.setOnAction(e -> rulesStage.hide());
        HBox actions = new HBox(done);
        actions.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(title, ruleGrid, actions);
        enableModalDrag(root, rulesStage);

        Scene scene = new Scene(root, 920, 360);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
        rulesStage.setScene(scene);
        rulesStage.show();
    }

    private void enableModalDrag(Node node, Stage target) {
        final double[] offset = new double[2];
        node.setOnMousePressed(e -> {
            offset[0] = e.getSceneX();
            offset[1] = e.getSceneY();
        });
        node.setOnMouseDragged(e -> {
            target.setX(e.getScreenX() - offset[0]);
            target.setY(e.getScreenY() - offset[1]);
        });
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
                mutedText("Mapping files help recover protected stack traces.")
        );
        return box;
    }

    private Node categoryPage(String category) {
        ensureCategorySelection(category);

        HBox page = new HBox(18);
        page.getStyleClass().add("page");

        VBox passCard = card(categoryTitle(category), categorySubtitle(category));
        passCard.setPrefWidth(560);
        passCard.setMinWidth(460);

        HBox tools = new HBox(10);
        tools.setAlignment(Pos.CENTER_LEFT);
        Button allOff = secondaryButton("All Off");
        allOff.setOnAction(e -> setCategoryEnabled(category, false));
        Button recommended = secondaryButton("Basic On");
        recommended.setOnAction(e -> enableCategoryBasic(category));
        HBox.setHgrow(transformerSearch, Priority.ALWAYS);
        tools.getChildren().addAll(allOff, transformerSearch, recommended);

        FlowPane passGrid = new FlowPane(12, 12);
        passGrid.getStyleClass().add("pass-grid");
        for (String name : passesForCategory(category)) {
            passGrid.getChildren().add(passTile(name));
        }

        ScrollPane passScroll = new ScrollPane(passGrid);
        passScroll.getStyleClass().add("pass-scroll");
        passScroll.setFitToWidth(true);
        passScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(passScroll, Priority.ALWAYS);
        passCard.getChildren().addAll(tools, passScroll);

        VBox detailCard = card("Settings", "Only the selected pass");
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
        settingsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(settingsScroll, Priority.ALWAYS);
        detailCard.getChildren().addAll(detailHeader, settingsScroll);

        HBox.setHgrow(detailCard, Priority.ALWAYS);
        page.getChildren().addAll(passCard, detailCard);
        return page;
    }

    private Node passTile(String name) {
        TransformerMeta meta = metaFor(name);
        TransformerConfig tc = config.getTransformers().get(name);
        VBox tile = new VBox(10);
        tile.getStyleClass().add("pass-tile");
        if (name.equals(selectedTransformer)) {
            tile.getStyleClass().add("selected");
        }

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);
        CheckBox enabled = checkBox("");
        enabled.setSelected(tc != null && tc.isEnabled());
        Label title = new Label(meta.title);
        title.getStyleClass().add("transformer-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label category = new Label(meta.category);
        category.getStyleClass().add("transformer-category");
        top.getChildren().addAll(enabled, title, spacer, category);

        Label summary = new Label(meta.summary);
        summary.getStyleClass().add("transformer-summary");
        summary.setWrapText(true);
        tile.getChildren().addAll(top, summary);
        installHoverMotion(tile);

        enabled.setOnAction(e -> {
            TransformerConfig current = config.getTransformers().get(name);
            if (current != null) {
                current.setEnabled(enabled.isSelected());
                markCustom();
                selectTransformer(name);
                showPage(currentPage, false);
            }
        });
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                selectTransformer(name);
                showPage(currentPage, false);
            }
        });
        return tile;
    }

    private void ensureCategorySelection(String category) {
        List<String> names = passesForCategory(category);
        if (names.isEmpty()) {
            selectedTransformer = null;
            settingsBox.getChildren().setAll(emptySettings("No passes in this category."));
            return;
        }
        if (selectedTransformer == null || !names.contains(selectedTransformer)) {
            selectTransformer(names.get(0));
        }
    }

    private List<String> passesForCategory(String category) {
        String filter = transformerSearch.getText() == null ? "" : transformerSearch.getText().trim().toLowerCase(Locale.ROOT);
        return TransformerRegistry.getAllNames().stream()
                .filter(name -> pageOwnsPass(category, metaFor(name).category))
                .filter(name -> {
                    TransformerMeta meta = metaFor(name);
                    String haystack = (name + " " + meta.title + " " + meta.category + " " + meta.summary).toLowerCase(Locale.ROOT);
                    return filter.isEmpty() || haystack.contains(filter);
                })
                .toList();
    }

    private boolean pageOwnsPass(String page, String category) {
        return switch (page) {
            case "protection" -> category.equals("Protection") || category.equals("Ownership");
            case "resources" -> category.equals("Resources");
            case "funsies" -> category.equals("Funsies");
            case "optimization" -> category.equals("Optimization");
            case "reporting" -> category.equals("Reporting");
            default -> category.equals("Renaming")
                    || category.equals("Cleanup")
                    || category.equals("Constants")
                    || category.equals("Flow")
                    || category.equals("Bytecode")
                    || category.equals("Calls")
                    || category.equals("Metadata");
        };
    }

    private boolean isCategoryPage(String page) {
        return page.equals("obfuscation")
                || page.equals("protection")
                || page.equals("resources")
                || page.equals("funsies")
                || page.equals("optimization")
                || page.equals("reporting");
    }

    private String categoryTitle(String page) {
        return switch (page) {
            case "protection" -> "Protection";
            case "resources" -> "Resources";
            case "funsies" -> "Funsies";
            case "optimization" -> "Optimize";
            case "reporting" -> "Reports";
            default -> "Obfuscation";
        };
    }

    private String categorySubtitle(String page) {
        return switch (page) {
            case "protection" -> "Watermarks, integrity, anti-debug, decoys, and traps.";
            case "resources" -> "Compression, encryption, and resource handling.";
            case "funsies" -> "Fun noise, banners, and chaos modes.";
            case "optimization" -> "Shrinking and bytecode cleanup.";
            case "reporting" -> "JSON and HTML run reports.";
            default -> "Renaming, encryption, flow, calls, and metadata.";
        };
    }

    private Node nativeProtectionPage() {
        compactNativeAreas();

        VBox page = new VBox(14);
        page.getStyleClass().add("page");
        page.setFillWidth(true);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("card-grid");
        grid.setHgap(14);
        grid.setVgap(14);
        grid.getColumnConstraints().addAll(percentColumn(50), percentColumn(50));

        VBox overview = card("Native Protection", "JNI conversion runs after Java transformers");
        overview.getChildren().addAll(
                nativeEnabledBox,
                mutedText("Selected methods are moved into generated C++ and replaced with native stubs."),
                labeledControl("Conversion mode", nativeModeBox),
                labeledControl("Compile mode", nativeCompileModeBox),
                labeledControl("Library name", nativeLibraryField),
                labeledControl("Optimization", nativeOptimizationBox),
                nativeResourceEmbeddingBox
        );

        VBox compiler = card("Compiler Detection", "Auto-detect local toolchains");
        nativeCompilerStatusLabel.getStyleClass().add("muted-label");
        nativeCompilerStatusLabel.setWrapText(true);
        Button detect = secondaryButton("Detect Compilers");
        detect.setOnAction(e -> detectNativeCompilers(true));
        Button installMinGw = secondaryButton("Install MinGW");
        installMinGw.setOnAction(e -> openExternal("https://www.msys2.org/"));
        Button installMsvc = secondaryButton("Install MSVC");
        installMsvc.setOnAction(e -> openExternal("https://visualstudio.microsoft.com/visual-cpp-build-tools/"));
        Button installClang = secondaryButton("Install Clang");
        installClang.setOnAction(e -> openExternal("https://github.com/llvm/llvm-project/releases"));
        FlowPane compilerActions = new FlowPane(8, 8, detect, installMinGw, installMsvc, installClang);
        compiler.getChildren().addAll(
                nativeCompilerStatusLabel,
                compilerActions,
                nativeUseClangBox,
                nativeUseGccBox,
                nativeUseMsvcBox,
                nativeStripSymbolsBox,
                nativeUnityBuildBox,
                nativeKeepSourcesBox,
                nativeDebugBox,
                fileRow("Work directory", nativeTempDirField, this::browseNativeTempDirectory)
        );

        VBox failures = card("Failure Policy", "Build behavior");
        failures.getChildren().addAll(
                nativeFailFastBox,
                nativeContinueBox,
                mutedText("Continue mode leaves Java bodies intact if native compilation fails.")
        );

        grid.add(overview, 0, 0);
        grid.add(compiler, 1, 0);
        grid.add(failures, 0, 1, 2, 1);
        growInGrid(overview);
        growInGrid(compiler);
        growInGrid(failures);

        GridPane filters = new GridPane();
        filters.getStyleClass().add("card-grid");
        filters.setHgap(14);
        filters.setVgap(14);
        filters.getColumnConstraints().addAll(percentColumn(50), percentColumn(50));

        VBox include = card("Include", "Classes, packages, methods, annotations");
        Button addClass = secondaryButton("Add Class");
        addClass.setOnAction(e -> showNativeSelectionDialog(false));
        Button addPackage = secondaryButton("Add Package");
        addPackage.setOnAction(e -> showNativeSelectionDialog(true));
        FlowPane includeActions = new FlowPane(8, 8, addClass, addPackage);
        include.getChildren().addAll(
                includeActions,
                labeledControl("Packages", nativeIncludePackagesArea),
                labeledControl("Classes", nativeIncludeClassesArea),
                labeledControl("Methods", nativeIncludeMethodsArea),
                labeledControl("Annotations", nativeIncludeAnnotationsArea)
        );

        VBox exclude = card("Exclude", "Exclusions always win");
        exclude.getChildren().addAll(
                labeledControl("Packages", nativeExcludePackagesArea),
                labeledControl("Classes", nativeExcludeClassesArea),
                labeledControl("Annotations", nativeExcludeAnnotationsArea)
        );

        filters.add(include, 0, 0);
        filters.add(exclude, 1, 0);
        growInGrid(include);
        growInGrid(exclude);

        VBox guide = card("New User Checklist", "What to install before enabling native protection");
        guide.getChildren().addAll(
                mutedText("Windows: MSYS2 UCRT64 MinGW is the easiest path. Install MSYS2, open the UCRT64 shell, install gcc, then make sure C:\\msys64\\ucrt64\\bin is on PATH."),
                mutedText("MSVC: install Visual Studio Build Tools with Desktop development with C++ and a Windows SDK."),
                mutedText("Clang: plain LLVM Clang still needs Windows C/C++ runtime headers. If Clang is detected but fails, install MSVC Build Tools or use MinGW.")
        );

        page.getChildren().addAll(grid, filters, guide);

        ScrollPane scroll = new ScrollPane(page);
        scroll.getStyleClass().add("pass-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        Platform.runLater(() -> detectNativeCompilers(false));
        return scroll;
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
        nativeEnabledBox.selectedProperty().addListener((obs, old, value) -> {
            if (refreshingControls || !value) {
                return;
            }
            if (!confirmNativeProtection()) {
                nativeEnabledBox.setSelected(false);
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
        loadNativeConfigIntoUi(cfg.getFrostJNI());
        List<String> names = passesForCategory(currentCategory);
        if (!names.isEmpty()) {
            selectTransformer(names.get(0));
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
        cfg.setFrostJNI(buildNativeConfigFromUi());
        return cfg;
    }

    private void loadNativeConfigIntoUi(FrostJNIConfig nativeConfig) {
        refreshingControls = true;
        nativeEnabledBox.setSelected(nativeConfig.isEnabled());
        nativeLibraryField.setText(nativeConfig.getOutputLibraryName());
        nativeTempDirField.setText(nativeConfig.getTemporaryDirectory());
        nativeModeBox.setValue(nativeConfig.getMode());
        nativeCompileModeBox.setValue(nativeConfig.getCompileMode());
        nativeOptimizationBox.setValue(nativeConfig.getOptimizationLevel());
        nativeUseClangBox.setSelected(nativeConfig.isUseClang());
        nativeUseGccBox.setSelected(nativeConfig.isUseGcc());
        nativeUseMsvcBox.setSelected(nativeConfig.isUseMsvc());
        nativeStripSymbolsBox.setSelected(nativeConfig.isStripSymbols());
        nativeUnityBuildBox.setSelected(nativeConfig.isUnityBuild());
        nativeKeepSourcesBox.setSelected(nativeConfig.isKeepGeneratedSources());
        nativeDebugBox.setSelected(nativeConfig.isDebugMode());
        nativeResourceEmbeddingBox.setSelected(nativeConfig.isResourceEmbedding());
        nativeFailFastBox.setSelected(nativeConfig.isFailFast());
        nativeContinueBox.setSelected(nativeConfig.isContinueOnFailure());
        nativeIncludePackagesArea.setText(String.join("\n", nativeConfig.getIncludePackages()));
        nativeIncludeClassesArea.setText(String.join("\n", nativeConfig.getIncludeClasses()));
        nativeIncludeMethodsArea.setText(String.join("\n", nativeConfig.getIncludeMethods()));
        nativeIncludeAnnotationsArea.setText(String.join("\n", nativeConfig.getIncludeAnnotations()));
        nativeExcludePackagesArea.setText(String.join("\n", nativeConfig.getExcludedPackages()));
        nativeExcludeClassesArea.setText(String.join("\n", nativeConfig.getExcludedClasses()));
        nativeExcludeAnnotationsArea.setText(String.join("\n", nativeConfig.getExcludedAnnotations()));
        refreshingControls = false;
    }

    private FrostJNIConfig buildNativeConfigFromUi() {
        FrostJNIConfig nativeConfig = new FrostJNIConfig();
        nativeConfig.setEnabled(nativeEnabledBox.isSelected());
        nativeConfig.setOutputLibraryName(nativeLibraryField.getText().trim());
        nativeConfig.setTemporaryDirectory(nativeTempDirField.getText().trim());
        nativeConfig.setMode(nativeModeBox.getValue());
        nativeConfig.setCompileMode(nativeCompileModeBox.getValue());
        nativeConfig.setOptimizationLevel(nativeOptimizationBox.getValue());
        nativeConfig.setUseClang(nativeUseClangBox.isSelected());
        nativeConfig.setUseGcc(nativeUseGccBox.isSelected());
        nativeConfig.setUseMsvc(nativeUseMsvcBox.isSelected());
        nativeConfig.setStripSymbols(nativeStripSymbolsBox.isSelected());
        nativeConfig.setUnityBuild(nativeUnityBuildBox.isSelected());
        nativeConfig.setKeepGeneratedSources(nativeKeepSourcesBox.isSelected());
        nativeConfig.setDebugMode(nativeDebugBox.isSelected());
        nativeConfig.setResourceEmbedding(nativeResourceEmbeddingBox.isSelected());
        nativeConfig.setFailFast(nativeFailFastBox.isSelected());
        nativeConfig.setContinueOnFailure(nativeContinueBox.isSelected());
        nativeConfig.setIncludePackages(lines(nativeIncludePackagesArea));
        nativeConfig.setIncludeClasses(lines(nativeIncludeClassesArea));
        nativeConfig.setIncludeMethods(lines(nativeIncludeMethodsArea));
        nativeConfig.setIncludeAnnotations(lines(nativeIncludeAnnotationsArea));
        nativeConfig.setExcludedPackages(lines(nativeExcludePackagesArea));
        nativeConfig.setExcludedClasses(lines(nativeExcludeClassesArea));
        nativeConfig.setExcludedAnnotations(lines(nativeExcludeAnnotationsArea));
        return nativeConfig;
    }

    private boolean confirmNativeProtection() {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(stage);
        alert.setTitle("Enable FrostJNI");
        alert.setHeaderText("Native protection changes the build and runtime model.");
        alert.setContentText("""
                Native protection increases build complexity and build time.
                Generated libraries are platform dependent.
                Debugging becomes harder.
                Antivirus software may flag native loaders.
                DLL/SO/DYLIB packaging must match your target platform.
                Reflection-heavy libraries may require exclusions.
                """);
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void compactNativeAreas() {
        for (TextArea area : List.of(
                nativeIncludePackagesArea,
                nativeIncludeClassesArea,
                nativeIncludeMethodsArea,
                nativeIncludeAnnotationsArea,
                nativeExcludePackagesArea,
                nativeExcludeClassesArea,
                nativeExcludeAnnotationsArea
        )) {
            area.setPrefRowCount(2);
            area.setMinHeight(58);
            area.setMaxHeight(92);
        }
    }

    private void detectNativeCompilers(boolean notify) {
        try {
            TargetPlatform target = TargetPlatform.current();
            List<DetectedCompiler> detected = new CompilerDetector().detectAll(target);
            Set<CompilerKind> allowed = allowedNativeCompilers();
            List<DetectedCompiler> enabled = detected.stream()
                    .filter(compiler -> allowed.contains(compiler.kind()))
                    .toList();

            if (detected.isEmpty()) {
                nativeCompilerStatusLabel.setText("No compiler found for " + target.operatingSystem().resourceName()
                        + "/" + target.architecture().resourceName()
                        + ". Install MinGW, MSVC Build Tools, or Clang with runtime headers.");
                if (notify) {
                    showToast("No native compiler found");
                }
                return;
            }

            StringBuilder builder = new StringBuilder();
            builder.append("Detected for ")
                    .append(target.operatingSystem().resourceName())
                    .append('/')
                    .append(target.architecture().resourceName())
                    .append(":\n");
            for (DetectedCompiler compiler : detected) {
                builder.append("- ")
                        .append(compiler.displayName())
                        .append(" at ")
                        .append(compiler.executable());
                if (!allowed.contains(compiler.kind())) {
                    builder.append(" (disabled)");
                }
                builder.append('\n');
            }
            if (enabled.isEmpty()) {
                builder.append("All detected compilers are disabled. Enable at least one compiler checkbox.");
                if (notify) {
                    showToast("Compilers found but disabled");
                }
            } else {
                builder.append("FrostJNI will try enabled compilers in detected order.");
                if (notify) {
                    showToast("Native compiler detected");
                }
            }
            nativeCompilerStatusLabel.setText(builder.toString());
        } catch (Exception exception) {
            nativeCompilerStatusLabel.setText("Compiler detection failed: " + exception.getMessage());
            if (notify) {
                showError(exception);
            }
        }
    }

    private void showNativeSelectionDialog(boolean packageMode) {
        try {
            Path input = Path.of(inputField.getText().trim());
            if (!Files.isRegularFile(input)) {
                showError(new IllegalStateException("Select an input jar before adding FrostJNI classes or packages."));
                return;
            }
            List<String> items = packageMode ? scanInputPackages(input) : scanInputClasses(input);
            if (items.isEmpty()) {
                showError(new IllegalStateException("No " + (packageMode ? "packages" : "classes") + " found in input jar."));
                return;
            }

            Stage picker = new Stage(StageStyle.TRANSPARENT);
            picker.initOwner(stage);
            picker.initModality(Modality.APPLICATION_MODAL);

            TextField search = textField(packageMode ? "Search packages" : "Search classes");
            ListView<String> list = new ListView<>();
            list.getStyleClass().add("selection-list");
            Runnable refresh = () -> {
                String filter = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
                list.getItems().setAll(items.stream()
                        .filter(item -> filter.isBlank() || item.toLowerCase(Locale.ROOT).contains(filter))
                        .toList());
            };
            search.textProperty().addListener((obs, old, value) -> refresh.run());
            refresh.run();

            Button add = primaryButton(packageMode ? "Add Package" : "Add Class");
            add.setOnAction(e -> {
                String selected = list.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    return;
                }
                appendNativeSelection(packageMode ? nativeIncludePackagesArea : nativeIncludeClassesArea, selected);
                picker.close();
                showToast((packageMode ? "Package" : "Class") + " added to FrostJNI");
            });
            Button cancel = secondaryButton("Cancel");
            cancel.setOnAction(e -> picker.close());
            list.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                    add.fire();
                }
            });

            VBox root = card(packageMode ? "Add Package" : "Add Class",
                    packageMode ? "Selecting a package protects all classes in it." : "Pick a specific class to protect.");
            root.getChildren().addAll(search, list, new HBox(8, cancel, add));
            root.setPrefSize(720, 560);
            enableModalDrag(root, picker);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
            picker.setScene(scene);
            picker.show();
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private List<String> scanInputClasses(Path input) throws IOException {
        List<String> classes = new ArrayList<>();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(input.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    String name = entry.getName().substring(0, entry.getName().length() - ".class".length())
                            .replace('/', '.');
                    if (!name.equals("module-info") && !name.endsWith(".package-info")) {
                        classes.add(name);
                    }
                }
            }
        }
        classes.sort(String::compareToIgnoreCase);
        return classes;
    }

    private List<String> scanInputPackages(Path input) throws IOException {
        java.util.Set<String> packages = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String className : scanInputClasses(input)) {
            int lastDot = className.lastIndexOf('.');
            packages.add(lastDot == -1 ? "(default package)" : className.substring(0, lastDot));
        }
        return new ArrayList<>(packages);
    }

    private void appendNativeSelection(TextArea area, String value) {
        if ("(default package)".equals(value)) {
            value = "";
        }
        List<String> current = new ArrayList<>(lines(area));
        if (!value.isBlank() && !current.contains(value)) {
            current.add(value);
        }
        area.setText(String.join("\n", current));
    }

    private Set<CompilerKind> allowedNativeCompilers() {
        Set<CompilerKind> allowed = new java.util.LinkedHashSet<>();
        if (nativeUseClangBox.isSelected()) {
            allowed.add(CompilerKind.CLANG);
        }
        if (nativeUseGccBox.isSelected()) {
            allowed.add(CompilerKind.GCC);
        }
        if (nativeUseMsvcBox.isSelected()) {
            allowed.add(CompilerKind.MSVC);
        }
        return allowed;
    }

    private void openExternal(String url) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                showError(new IllegalStateException("Opening links is not supported on this system."));
                return;
            }
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception exception) {
            showError(exception);
        }
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
            case "obfuscation", "protection", "resources", "funsies", "optimization", "reporting" -> {
                currentCategory = page;
                pageTitle.setText(categoryTitle(page));
                pageSubtitle.setText(categorySubtitle(page));
                node = categoryPage(page);
            }
            case "native-protection" -> {
                pageTitle.setText("Native Protection");
                pageSubtitle.setText("FrostJNI compiler, loader, and native conversion settings.");
                node = nativeProtectionPage();
            }
            case "console" -> {
                pageTitle.setText("Console");
                pageSubtitle.setText("Live run output and status.");
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
        FadeTransition fade = new FadeTransition(Duration.millis(95), node);
        fade.setToValue(1);
        node.setScaleX(0.995);
        node.setScaleY(0.995);
        ScaleTransition scale = new ScaleTransition(Duration.millis(120), node);
        scale.setToX(1);
        scale.setToY(1);
        new ParallelTransition(fade, scale).play();
    }

    private void refreshTransformerList() {
        if (isCategoryPage(currentPage)) {
            showPage(currentPage, false);
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
            case TEXT -> textSetting(tc, spec);
        };
        row.getChildren().addAll(label, control, help);
        return row;
    }

    private Node textSetting(TransformerConfig tc, OptionSpec spec) {
        TextField field = textField(String.valueOf(spec.defaultValue));
        field.setText(stringValue(tc.getOptions().get(spec.key), String.valueOf(spec.defaultValue)));
        field.textProperty().addListener((obs, old, value) -> {
            tc.getOptions().put(spec.key, value);
            markCustom();
        });
        return field;
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
        String current = stringValue(tc.getOptions().get(spec.key), String.valueOf(spec.defaultValue));
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(spec.choices));
        combo.getStyleClass().add("combo-box");
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : pretty(item));
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : pretty(item));
            }
        });
        String selected = spec.choices.stream()
                .filter(option -> option.equalsIgnoreCase(current))
                .findFirst()
                .orElse(String.valueOf(spec.defaultValue));
        if (!spec.choices.contains(selected) && !spec.choices.isEmpty()) {
            selected = spec.choices.get(0);
        }
        combo.setValue(selected);
        tc.getOptions().put(spec.key, selected);
        combo.valueProperty().addListener((obs, old, value) -> {
            if (value == null) return;
            tc.getOptions().put(spec.key, value);
            markCustom();
        });
        return combo;
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
            case "none" -> applyNoPassPreset();
            case "basic" -> applyBasicPreset();
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
        pulse(topPresetLabel);
    }

    private void applyNoPassPreset() {
        dictionaryBox.setValue("alphabet");
        packageModeBox.setValue("keep");
        flattenPackageField.setText("obf");
        for (String name : TransformerRegistry.getAllNames()) {
            setPreset(name, false, config.getTransformers().getOrDefault(name, new TransformerConfig()).getOptions());
        }
    }

    private void applyBasicPreset() {
        applyNoPassPreset();
        setPreset("remove-debug", true, options("remove-source-file", true, "remove-line-numbers", true, "remove-local-variables", true, "remove-parameters", true));
        setPreset("string-encryption", true, options("mode", "lite", "min-length", 2, "max-method-instructions", 6000));
        setPreset("class-rename", true, options("mode", "safe"));
        setPreset("field-rename", true, options("mode", "safe"));
        setPreset("method-rename", true, options("mode", "safe"));
        setPreset("watermark", false, options("owner", "unknown", "id", "change-me", "class-annotations", true, "string-field", true, "field-name", "__frost$watermark"));
        setPreset("integrity", false, options());
        setPreset("statistics-report", false, options("format", "json", "output", "frost-report.json"));
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
        setPreset("flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 8, "max-predicates-per-method", 24, "min-method-instructions", 12, "max-method-instructions", 5000));
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
        setCommonProtectionPreset(true);
    }

    private void applyStrongPreset() {
        applyBalancedPreset();
        setPreset("string-encryption", true, options("mode", "condy", "min-length", 1, "max-method-instructions", 6000));
        setPreset("number-obfuscation", true, options("probability", 90, "max-per-method", 128, "max-per-class", 320, "max-method-instructions", 6000));
        setPreset("parameter-encryption", true, options("probability", 45));
        setPreset("flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 12, "max-predicates-per-method", 32, "min-method-instructions", 12, "max-method-instructions", 5000));
        setPreset("flow-outliner", true, options("probability", 35, "max-per-class", 22));
        setPreset("flow-range", true, options("probability", 48));
        setPreset("flow-condition", true, options("probability", 38, "max-per-method", 24));
        setPreset("flow-exception", true, options("strength", "AGGRESSIVE"));
        setPreset("stack-manipulation", true, options("probability", 11, "max-per-method", 22));
        setPreset("invoke-dynamic", true, options("probability", 55, "mutable-callsites", true));
        setPreset("reference-hiding", true, options("probability", 65, "max-per-class", 144, "max-method-instructions", 6000));
        setPreset("metadata-noise", true, options("strings-per-class", 12, "deprecated", true, "signatures", true));
        setPreset("anti-debug", true, antiDebugOptions(false));
        setPreset("anti-decompiler", true, options());
        setPreset("junk-code", true, options("min-methods-per-class", 1, "max-methods-per-class", 3, "min-fields-per-class", 0, "max-fields-per-class", 2, "seed", 0));
        setPreset("bytecode-optimizer", true, options());
    }

    private void applyMaximumPreset() {
        applyStrongPreset();
        setPreset("string-encryption", true, options("mode", "polymorphic", "min-length", 1, "max-method-instructions", 6000));
        setPreset("number-obfuscation", true, options("probability", 100, "max-per-method", 160, "max-per-class", 512, "max-method-instructions", 6000));
        setPreset("parameter-encryption", true, options("probability", 60));
        setPreset("flow-obfuscation", true, options("mode", "heavy", "exception-guards", true, "stack-noise", true, "flatten", true, "predicate-rate", 16, "max-predicates-per-method", 40, "min-method-instructions", 12, "max-method-instructions", 5000));
        setPreset("flow-outliner", true, options("probability", 45, "max-per-class", 28));
        setPreset("flow-range", true, options("probability", 60));
        setPreset("flow-condition", true, options("probability", 50, "max-per-method", 32));
        setPreset("flow-switch", true, options("probability", 90));
        setPreset("stack-manipulation", true, options("probability", 14, "max-per-method", 28));
        setPreset("invoke-dynamic", true, options("probability", 75, "mutable-callsites", true));
        setPreset("reference-hiding", true, options("probability", 80, "max-per-class", 192, "max-method-instructions", 6000));
        setPreset("metadata-noise", true, options("strings-per-class", 18, "deprecated", true, "signatures", true));
        setPreset("anti-debug", true, antiDebugOptions(true));
        setPreset("junk-code", true, options("min-methods-per-class", 2, "max-methods-per-class", 5, "min-fields-per-class", 1, "max-fields-per-class", 3, "seed", 0));
        setPreset("fake-classes", true, fakeClassOptions(24, 10, 24, 2, 8));
        setPreset("jar-shrinker", true, options());
        setPreset("resource-compression", true, options("remove-originals", true, "output-prefix", "META-INF/frostfuscator/resources/"));
        setPreset("resource-encryption", true, options("remove-originals", false, "output-prefix", "META-INF/frostfuscator/encrypted/", "seed", 0));
    }

    private void setCommonProtectionPreset(boolean enabled) {
        setPreset("watermark", enabled, options("owner", "unknown", "id", "change-me", "class-annotations", true, "string-field", true, "field-name", "__frost$watermark"));
        setPreset("integrity", enabled, options());
        setPreset("anti-debug", false, antiDebugOptions(false));
        setPreset("anti-decompiler", enabled, options());
        setPreset("junk-code", enabled, options("min-methods-per-class", 1, "max-methods-per-class", 2, "min-fields-per-class", 0, "max-fields-per-class", 1, "seed", 0));
        setPreset("fake-classes", false, fakeClassOptions(12, 8, 16, 2, 4));
        setPreset("inject-banner", false, options("text", "Protected by Frostfuscator", "copies", 1));
        setPreset("emoji-hell", false, options("copies", 3));
        setPreset("copypasta-injector", false, options("copies", 3));
        setPreset("fake-application", false, fakeApplicationOptions());
        setPreset("chinese-mode", false, chineseModeOptions());
        setPreset("resource-compression", false, options("remove-originals", true, "output-prefix", "META-INF/frostfuscator/resources/"));
        setPreset("resource-encryption", false, options("remove-originals", false, "output-prefix", "META-INF/frostfuscator/encrypted/", "seed", 0));
        setPreset("bytecode-optimizer", enabled, options());
        setPreset("jar-shrinker", false, options());
        setPreset("statistics-report", enabled, options("format", "json", "output", "frost-report.json"));
    }

    private Map<String, Object> antiDebugOptions(boolean processChecks) {
        return options(
                "method-name", "__frost$antiDebug",
                "check-arguments", true,
                "check-debug-classes", true,
                "check-stack", true,
                "check-timing", true,
                "shared-helper", true,
                "timing-iterations", 1000000,
                "timing-threshold-ms", 80,
                "check-processes", processChecks
        );
    }

    private Map<String, Object> fakeClassOptions(int count, int minMethods, int maxMethods, int minFields, int maxFields) {
        return options(
                "priority", "pre-obfuscation",
                "count", count,
                "min-methods-per-class", minMethods,
                "max-methods-per-class", maxMethods,
                "min-fields-per-class", minFields,
                "max-fields-per-class", maxFields,
                "kind-ratio", "regular:70,interface:10,enum:10,inner:10",
                "placement", "package-mode",
                "naming", "dictionary",
                "custom-pattern", "Fake{index}",
                "package", "frost/junk",
                "seed", 0
        );
    }

    private Map<String, Object> fakeApplicationOptions() {
        return options(
                "profiles", "minecraft-plugin,networking-stack,enterprise",
                "classes-per-profile", 3,
                "min-methods-per-class", 8,
                "max-methods-per-class", 24,
                "min-fields-per-class", 3,
                "max-fields-per-class", 10,
                "seed", 0
        );
    }

    private Map<String, Object> chineseModeOptions() {
        return options(
                "package-mode", "random",
                "package-prefix", "\u51b0\u971c/\u6df7\u6dc6\u5668",
                "rename-members", true,
                "inject-fun", true,
                "large-banners", true,
                "quotes", true,
                "inject-metadata", true,
                "inject-strings", true,
                "min-fun-members", 1,
                "max-fun-members", 3
        );
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

    private void setCategoryEnabled(String category, boolean enabled) {
        for (String name : passesForCategory(category)) {
            TransformerConfig tc = config.getTransformers().get(name);
            if (tc != null) {
                tc.setEnabled(enabled);
            }
        }
        markCustom();
        showPage(currentPage, false);
        updateStats();
    }

    private void enableCategoryBasic(String category) {
        setCategoryEnabled(category, false);
        switch (category) {
            case "protection" -> {
                setPreset("watermark", true, options("owner", "unknown", "id", "change-me", "class-annotations", true, "string-field", true, "field-name", "__frost$watermark"));
                setPreset("integrity", true, options());
                setPreset("junk-code", true, options("min-methods-per-class", 1, "max-methods-per-class", 2, "min-fields-per-class", 0, "max-fields-per-class", 1, "seed", 0));
            }
            case "resources" -> {
                setPreset("resource-compression", true, options("remove-originals", true, "output-prefix", "META-INF/frostfuscator/resources/"));
                setPreset("resource-encryption", false, options("remove-originals", false, "output-prefix", "META-INF/frostfuscator/encrypted/", "seed", 0));
            }
            case "funsies" -> {
                setPreset("inject-banner", true, options("text", "Protected by Frostfuscator", "copies", 1));
                setPreset("copypasta-injector", true, options("copies", 3));
            }
            case "optimization" -> setPreset("bytecode-optimizer", true, options());
            case "reporting" -> setPreset("statistics-report", true, options("format", "json", "output", "frost-report.json"));
            default -> {
                setPreset("remove-debug", true, options("remove-source-file", true, "remove-line-numbers", true, "remove-local-variables", true, "remove-parameters", true));
                setPreset("string-encryption", true, options("mode", "lite", "min-length", 2, "max-method-instructions", 6000));
                setPreset("class-rename", true, options("mode", "safe"));
            }
        }
        markCustom();
        showPage(currentPage, false);
        updateStats();
    }

    private void runProtection(Button runButton, Button validateButton) {
        ObfuscationConfig runConfig = buildConfigFromUi();
        try {
            ConfigLoader.validate(runConfig);
            validateNativeConfiguration(runConfig);
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
                showToast("Protection run complete");
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
            validateNativeConfiguration(config);
            statusLabel.setText("Valid");
            showToast("Configuration is valid");
        } catch (Exception ex) {
            statusLabel.setText("Needs input");
            showError(ex);
        }
    }

    private void validateNativeConfiguration(ObfuscationConfig cfg) {
        FrostJNIConfig nativeConfig = cfg.getFrostJNI();
        if (nativeConfig == null || !nativeConfig.isEnabled()) {
            return;
        }
        Set<CompilerKind> allowed = allowedNativeCompilers();
        if (allowed.isEmpty()) {
            throw new IllegalStateException("FrostJNI is enabled, but every compiler family is disabled. Enable Clang, GCC/MinGW, or MSVC.");
        }
        if ("SELECTIVE".equalsIgnoreCase(nativeConfig.getMode())
                && nativeConfig.getIncludeClasses().isEmpty()
                && nativeConfig.getIncludePackages().isEmpty()
                && nativeConfig.getIncludeMethods().isEmpty()
                && nativeConfig.getIncludeAnnotations().isEmpty()) {
            throw new IllegalStateException("FrostJNI is in SELECTIVE mode but nothing is selected. Use Add Class, Add Package, or add an annotation filter.");
        }
        List<DetectedCompiler> detected = new CompilerDetector().detectAll(TargetPlatform.current()).stream()
                .filter(compiler -> allowed.contains(compiler.kind()))
                .toList();
        if (detected.isEmpty()) {
            throw new IllegalStateException("""
                    FrostJNI is enabled, but no enabled native compiler was detected.
                    Windows beginner path: install MSYS2, install the UCRT64 gcc package, and add C:\\msys64\\ucrt64\\bin to PATH.
                    Alternative: install Visual Studio Build Tools with Desktop development with C++ and a Windows SDK.
                    """);
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

    private void browseNativeTempDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select FrostJNI work directory");
        File current = currentFile(nativeTempDirField.getText());
        if (current != null && current.exists()) {
            chooser.setInitialDirectory(current.isDirectory() ? current : current.getParentFile());
        }
        File folder = chooser.showDialog(stage);
        if (folder != null) {
            nativeTempDirField.setText(folder.getAbsolutePath());
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
        String text = "none".equals(activePreset) ? "No Passes" : capitalize(activePreset);
        topPresetLabel.setText(text);
        double progress = switch (activePreset) {
            case "none" -> 0.0;
            case "basic" -> 0.18;
            case "strong" -> 0.74;
            case "maximum" -> 1.0;
            case "custom" -> 0.55;
            default -> 0.52;
        };
        if (presetStrengthBar != null) {
            presetStrengthBar.setProgress(progress);
        }
    }

    private void updateStats() {
        long enabled = config.getTransformers().values().stream().filter(TransformerConfig::isEnabled).count();
        int total = TransformerRegistry.getAllNames().size();
        String text = enabled + " / " + total + " passes enabled";
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
        Button button = new Button(subtitle == null || subtitle.isBlank() ? title : title + "\n" + subtitle);
        button.getStyleClass().add("nav-button");
        button.setAlignment(Pos.CENTER);
        button.setOnAction(e -> showPage(page, true));
        installHoverMotion(button);
        navButtons.put(page, button);
        return button;
    }

    private Button presetButton(String title, String preset) {
        Button button = new Button(title);
        button.getStyleClass().add("preset-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> applyPreset(preset));
        installHoverMotion(button);
        presetButtons.put(preset, button);
        return button;
    }

    private Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        installHoverMotion(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("secondary-button");
        installHoverMotion(button);
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
        area.setPrefRowCount(3);
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

    private void installHoverMotion(Node node) {
        node.setOnMouseEntered(e -> scaleTo(node, 1.018));
        node.setOnMouseExited(e -> scaleTo(node, 1.0));
    }

    private void scaleTo(Node node, double scale) {
        ScaleTransition transition = new ScaleTransition(Duration.millis(115), node);
        transition.setToX(scale);
        transition.setToY(scale);
        transition.play();
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
                tc.setEnabled(false);
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
        Path output = (parent == null ? Path.of("") : parent).resolve(name + "-protected.jar");
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
        specs.put("watermark", List.of(
                text("owner", "Owner", "unknown", "Owner or project name embedded in metadata."),
                text("id", "Identifier", "change-me", "Build, customer, or license identifier."),
                bool("class-annotations", "Class annotations", true, "Adds invisible class annotations."),
                bool("string-field", "String field", true, "Adds a synthetic watermark field."),
                text("field-name", "Field name", "__frost$watermark", "Synthetic field name.")
        ));
        specs.put("anti-debug", List.of(
                text("method-name", "Guard method", "__frost$antiDebug", "Generated guard method name."),
                bool("check-arguments", "JVM arguments", true, "Checks JDWP, Xdebug, and Java agents."),
                bool("check-debug-classes", "Debugger classes", true, "Checks common IDE debug agent classes."),
                bool("check-stack", "Stack trace", true, "Checks suspicious stack trace markers."),
                bool("check-timing", "Timing check", true, "Detects heavy single-step slowdown."),
                bool("shared-helper", "Shared helper", true, "Stores heavy guard logic in one generated class to reduce per-class bloat."),
                text("helper-class", "Helper class", "", "Optional internal name for the shared helper class."),
                integer("timing-iterations", "Timing iterations", 1000000, 1000, 10000000, 1000, "Loop size for timing detection."),
                integer("timing-threshold-ms", "Timing threshold", 80, 1, 5000, 1, "Maximum expected timing loop duration."),
                bool("check-processes", "Process scan", false, "Looks for common reverse-engineering tools.")
        ));
        specs.put("junk-code", List.of(
                integer("min-methods-per-class", "Min methods", 1, 0, 64, 1, "Minimum synthetic junk methods per class."),
                integer("max-methods-per-class", "Max methods", 3, 0, 64, 1, "Maximum synthetic junk methods per class."),
                integer("min-fields-per-class", "Min fields", 0, 0, 32, 1, "Minimum synthetic junk fields per class."),
                integer("max-fields-per-class", "Max fields", 2, 0, 32, 1, "Maximum synthetic junk fields per class."),
                integer("seed", "Seed", 0, 0, 10000000, 1, "0 uses fresh randomness for each run.")
        ));
        specs.put("fake-classes", List.of(
                choice("priority", "Priority", "pre-obfuscation", List.of("pre-obfuscation", "normal", "post-remap", "final"), "Generation runs before regular obfuscation."),
                integer("count", "Class count", 12, 0, 500, 1, "Number of decoy classes to add."),
                integer("min-methods-per-class", "Min methods", 8, 0, 200, 1, "Minimum synthetic methods per decoy class."),
                integer("max-methods-per-class", "Max methods", 16, 0, 200, 1, "Maximum synthetic methods per decoy class."),
                integer("min-fields-per-class", "Min fields", 2, 0, 64, 1, "Minimum synthetic fields per decoy class."),
                integer("max-fields-per-class", "Max fields", 4, 0, 64, 1, "Maximum synthetic fields per decoy class."),
                text("kind-ratio", "Kind ratio", "regular:70,interface:10,enum:10,inner:10", "Weights for regular/interface/enum-like/inner-shaped decoys."),
                choice("placement", "Placement", "package-mode", List.of("package-mode", "existing", "specific", "none"), "Where generated decoys are placed."),
                choice("naming", "Naming", "dictionary", List.of("dictionary", "custom", "confusable", "chinese"), "How fake class simple names are generated."),
                text("custom-pattern", "Custom pattern", "Fake{index}", "Used when naming is custom; supports {index} and {random}."),
                text("package", "Package", "frost/junk", "Used when placement is specific."),
                integer("seed", "Seed", 0, 0, 10000000, 1, "0 uses fresh randomness for each run.")
        ));
        specs.put("inject-banner", List.of(
                text("text", "Text", "Protected by Frostfuscator", "Custom ASCII/banner text to inject."),
                integer("copies", "Copies", 1, 1, 8, 1, "Number of banner fields/methods per class.")
        ));
        specs.put("emoji-hell", List.of(integer("copies", "Copies", 3, 1, 16, 1, "Emoji strings per class.")));
        specs.put("copypasta-injector", List.of(integer("copies", "Copies", 3, 1, 16, 1, "Copypasta strings per class.")));
        specs.put("fake-application", List.of(
                text("profiles", "Profiles", "minecraft-plugin,networking-stack,enterprise", "Comma-separated profiles to generate."),
                integer("classes-per-profile", "Classes per profile", 3, 1, 32, 1, "Classes generated for each selected profile."),
                integer("min-methods-per-class", "Min methods", 8, 1, 200, 1, "Minimum methods per generated profile class."),
                integer("max-methods-per-class", "Max methods", 24, 1, 200, 1, "Maximum methods per generated profile class."),
                integer("min-fields-per-class", "Min fields", 3, 0, 64, 1, "Minimum fields per generated profile class."),
                integer("max-fields-per-class", "Max fields", 10, 0, 64, 1, "Maximum fields per generated profile class."),
                integer("seed", "Seed", 0, 0, 10000000, 1, "0 uses fresh randomness for each run.")
        ));
        specs.put("chinese-mode", List.of(
                choice("package-mode", "Package mode", "random", List.of("random", "global", "existing", "none"), "Random creates fresh Chinese package paths; global uses the prefix below."),
                text("package-prefix", "Global package", "\u51b0\u971c/\u6df7\u6dc6\u5668", "Used only when package mode is global; leave blank for one random global package."),
                bool("rename-members", "Rename members", true, "Renames methods and fields to Chinese identifiers."),
                bool("inject-fun", "Inject fun", true, "Adds Chinese banners, strings, and methods to every class."),
                bool("large-banners", "Large banners", true, "Adds larger Chinese banner blocks."),
                bool("quotes", "Quotes", true, "Adds random Chinese quote strings."),
                bool("inject-metadata", "Metadata", true, "Adds Chinese source/debug metadata."),
                bool("inject-strings", "Strings", true, "Adds random Chinese strings."),
                integer("min-fun-members", "Min fun members", 1, 0, 16, 1, "Minimum Chinese fun methods/fields per class."),
                integer("max-fun-members", "Max fun members", 3, 0, 16, 1, "Maximum Chinese fun methods/fields per class.")
        ));
        specs.put("resource-compression", List.of(
                bool("remove-originals", "Remove originals", true, "Removes protected resource originals after compressed copies are written."),
                text("output-prefix", "Output prefix", "META-INF/frostfuscator/resources/", "Compressed resource location.")
        ));
        specs.put("resource-encryption", List.of(
                bool("remove-originals", "Remove originals", false, "Removes protected resource originals after encrypted copies are written."),
                text("output-prefix", "Output prefix", "META-INF/frostfuscator/encrypted/", "Encrypted resource location."),
                integer("seed", "Seed", 0, 0, 10000000, 1, "0 uses fresh randomness for each run.")
        ));
        specs.put("statistics-report", List.of(
                choice("format", "Format", "json", List.of("json", "html"), "Report output format."),
                text("output", "Output path", "frost-report.json", "Report file path.")
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

    private static OptionSpec text(String key, String label, String defaultValue, String help) {
        return new OptionSpec(key, label, OptionType.TEXT, defaultValue, 0, 0, 1, List.of(), help);
    }

    private enum OptionType {
        BOOLEAN,
        CHOICE,
        INTEGER,
        TEXT
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

