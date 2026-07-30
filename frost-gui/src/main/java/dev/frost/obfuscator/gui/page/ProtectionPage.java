package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.config.FrostJNIConfig;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.StatusChip;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.Motion;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.gui.protection.*;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.jni.compiler.DetectedCompiler;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Locale;

public final class ProtectionPage implements PageView {
    private final AppContext context;
    private final TransformerCatalog catalog = new TransformerCatalog();
    private final SchemaSettingsRenderer renderer = new SchemaSettingsRenderer();
    private final BorderPane root = new BorderPane();
    private final Motion motion;
    private final ListView<TransformerCatalog.Descriptor> transformerList = new ListView<>();
    private final TextField search = new TextField();
    private final Label transformerCount = Ui.label("", "muted-text");
    private final Label categoryDescription = Ui.label("", "category-description");
    private final CustomComboBox<TransformerCatalog.Category> category =
            new CustomComboBox<>(List.of(TransformerCatalog.Category.values()), TransformerCatalog.Category::display);
    private final VBox editor = new VBox(Ui.SPACE_8);
    private final VBox impactPanel = new VBox(Ui.SPACE_4);
    private VBox right;
    private HBox workspace;
    private Label enabledPassValue;
    private ProgressBar enabledPassProgress;
    private TransformerCatalog.Descriptor selected;
    private boolean initializing = true;

    public ProtectionPage(AppContext context) {
        this.context = context;
        this.motion = new Motion(context.themeManager());
        root.getStyleClass().addAll("page", "protection-page");
        root.setPadding(Ui.pageInsets());

        VBox pageHeader = Ui.pageHeader("Protection",
                "Choose a category and transformer, then tune only the settings that matter.");
        pageHeader.getStyleClass().add("protection-page-header");
        BorderPane.setMargin(pageHeader, new javafx.geometry.Insets(0, 0, Ui.SPACE_8, 0));
        root.setTop(pageHeader);

        VBox left = buildTransformerBrowser();
        ScrollPane editorScroll = Ui.pageScroll(editor);
        SmoothScroll.install(editorScroll, context.themeManager());
        editorScroll.getStyleClass().add("protection-editor-scroll");
        editorScroll.setMinWidth(0);
        editor.setMinWidth(0);
        editor.setFillWidth(true);
        editor.getStyleClass().add("transformer-editor");
        right = Ui.section("Compatibility & impact",
                "Project-aware guidance for the selected transformer.", impactPanel);
        right.getStyleClass().add("impact-panel");
        right.setMinWidth(260);
        right.setPrefWidth(300);
        right.setMaxWidth(340);

        workspace = new HBox(Ui.SPACE_6, left, editorScroll, right);
        workspace.getStyleClass().add("protection-workspace");
        workspace.setMinWidth(0);
        HBox.setHgrow(editorScroll, Priority.ALWAYS);
        root.setCenter(workspace);

        Runnable updateLayout = () -> {
            boolean wide = root.getWidth() >= 1320;
            right.setVisible(true);
            right.setManaged(true);
            if (wide) {
                if (editor.getChildren().contains(right)) {
                    editor.getChildren().remove(right);
                }
                if (!workspace.getChildren().contains(right)) {
                    workspace.getChildren().add(right);
                }
                right.setMinWidth(260);
                right.setMaxWidth(340);
            } else {
                if (workspace.getChildren().contains(right)) {
                    workspace.getChildren().remove(right);
                }
                if (!editor.getChildren().contains(right)) {
                    editor.getChildren().add(right);
                }
                right.setMinWidth(0);
                right.setMaxWidth(Double.MAX_VALUE);
            }
        };
        root.widthProperty().addListener((obs, old, val) -> updateLayout.run());

        search.textProperty().addListener((obs, old, value) -> refreshList());
        category.valueProperty().addListener((obs, old, value) -> {
            if (value != null) context.preferences().put("protection.category", value.name());
            refreshList();
        });
        transformerList.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (value != null) {
                context.preferences().put("protection.transformer", value.name());
                select(value);
            }
        });
        category.setValue(savedCategory());
        refreshList();
        initializing = false;
    }

    private VBox buildTransformerBrowser() {
        search.getStyleClass().add("text-input");
        search.setPromptText("Search transformers");
        category.getStyleClass().add("protection-category-combo");
        transformerList.getStyleClass().add("transformer-list");
        Label emptyTitle = Ui.label("No matching transformers", "profile-title");
        Label emptyCopy = Ui.label("Try a different category or clear the search.", "empty-state-copy");
        emptyCopy.setWrapText(true);
        VBox emptyState = new VBox(Ui.SPACE_2, emptyTitle, emptyCopy);
        emptyState.getStyleClass().add("transformer-empty-state");
        emptyState.setAlignment(Pos.CENTER);
        transformerList.setPlaceholder(emptyState);
        transformerList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(TransformerCatalog.Descriptor item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    setAccessibleText(null);
                } else {
                    Label title = Ui.label(item.title(), "transformer-row-title");
                    title.setWrapText(true);
                    title.setTextOverrun(OverrunStyle.CLIP);
                    title.setMinWidth(0);
                    title.setMaxWidth(Double.MAX_VALUE);
                    String state = enabled(item) ? "On" : "Off";
                    StatusChip chip = new StatusChip(state, enabled(item) ? "success" : "neutral");
                    HBox line = new HBox(Ui.SPACE_2, title, chip);
                    line.setAlignment(Pos.CENTER_LEFT);
                    line.setMinWidth(0);
                    HBox.setHgrow(title, Priority.ALWAYS);
                    Label description = Ui.label(item.description(), "transformer-row-description");
                    description.setWrapText(true);
                    description.setTextOverrun(OverrunStyle.CLIP);
                    description.setMinWidth(0);
                    description.setMinHeight(Region.USE_PREF_SIZE);
                    description.setMaxWidth(Double.MAX_VALUE);
                    description.prefWidthProperty().bind(transformerList.widthProperty().subtract(84));
                    VBox content = new VBox(Ui.SPACE_2, line, description);
                    content.setMinWidth(0);
                    content.setMinHeight(Region.USE_PREF_SIZE);
                    content.prefWidthProperty().bind(transformerList.widthProperty().subtract(68));
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    setAccessibleText(item.title() + ". " + state + ". " + item.description());
                    setGraphic(content);
                }
            }
        });
        transformerList.setFixedCellSize(Region.USE_COMPUTED_SIZE);
        VBox.setVgrow(transformerList, Priority.ALWAYS);
        categoryDescription.setWrapText(true);
        categoryDescription.setMinHeight(Region.USE_PREF_SIZE);
        VBox browserHeading = new VBox(Ui.SPACE_1,
                Ui.label("Transformers", "section-title"), transformerCount, categoryDescription);
        VBox left = new VBox(Ui.SPACE_4, browserHeading, category, search, transformerList);
        left.getStyleClass().add("transformer-browser");
        left.setMinWidth(360);
        left.setPrefWidth(380);
        left.setMaxWidth(420);
        return left;
    }

    private void refreshList() {
        TransformerCatalog.Category selectedCategory = category.getValue();
        if (selectedCategory == null) return;
        String filter = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<TransformerCatalog.Descriptor> items = catalog.category(selectedCategory).stream()
                .filter(item -> filter.isBlank() || item.title().toLowerCase(Locale.ROOT).contains(filter)
                        || item.description().toLowerCase(Locale.ROOT).contains(filter))
                .toList();
        transformerCount.setText(items.size() + " pass" + (items.size() == 1 ? "" : "es")
                + " in " + selectedCategory.display());
        categoryDescription.setText(selectedCategory.description());
        transformerList.setItems(FXCollections.observableArrayList(items));
        if (!items.isEmpty()) {
            String preferred = context.preferences().get("protection.transformer", "");
            TransformerCatalog.Descriptor match = items.stream()
                    .filter(item -> item.name().equals(preferred)).findFirst().orElse(items.getFirst());
            transformerList.getSelectionModel().select(match);
        }
    }

    private void select(TransformerCatalog.Descriptor descriptor) {
        selected = descriptor;
        editor.getChildren().clear();
        CheckBox enabled = new CheckBox("Enabled");
        enabled.getStyleClass().add("switch");
        enabled.setSelected(enabled(descriptor));
        enabled.selectedProperty().addListener((obs, old, value) -> {
            setEnabled(descriptor, value);
            context.projectState().profileProperty().set("Custom");
            context.projectState().touch();
            transformerList.refresh();
            refreshEnabledPassCount();
            refreshImpact();
        });
        Label title = Ui.label(descriptor.title(), "detail-title");
        title.setWrapText(true);
        title.setMinWidth(0);
        Label description = Ui.label(descriptor.description(), "detail-description");
        description.setWrapText(true);
        description.setMaxWidth(Double.MAX_VALUE);
        VBox titleCopy = new VBox(Ui.SPACE_2, title, description);
        titleCopy.setMinWidth(0);
        HBox.setHgrow(titleCopy, Priority.ALWAYS);
        HBox headerLine = new HBox(Ui.SPACE_6, titleCopy, enabled);
        headerLine.setAlignment(Pos.CENTER_LEFT);
        headerLine.setMinWidth(0);
        HBox meta = new HBox(Ui.SPACE_2,
                new StatusChip(descriptor.category().display(), "neutral"),
                new StatusChip(descriptor.compatibility(),
                        descriptor.compatibility().startsWith("Generally") ? "success" : "warning"));
        meta.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(Ui.SPACE_4, headerLine, meta);
        header.getStyleClass().add("transformer-detail-header");

        FlowPane presets = new FlowPane(Ui.SPACE_2, Ui.SPACE_2);
        presets.setAlignment(Pos.CENTER_LEFT);
        presets.getStyleClass().add("profile-presets");
        for (String name : List.of("Development", "Balanced", "Strong", "Maximum")) {
            Button button = Ui.button(name, "preset-button", () -> {
                ProtectionProfiles.apply(context.projectState(), name);
                select(descriptor);
                transformerList.refresh();
            });
            if (name.equalsIgnoreCase(context.projectState().profileProperty().get())) {
                button.getStyleClass().add("preset-button-selected");
            }
            presets.getChildren().add(button);
        }
        Label profileDescription = Ui.label(
                "Apply safe project-wide defaults. You can still tune this transformer afterwards.",
                "profile-description");
        profileDescription.setWrapText(true);
        VBox profileSection = new VBox(Ui.SPACE_3,
                Ui.label("Project profile", "profile-title"), profileDescription, presets);
        profileSection.getStyleClass().add("profile-section");
        profileSection.setMinWidth(0);
        profileSection.setMaxWidth(Double.MAX_VALUE);

        VBox passSummary = enabledPassCard();
        GridPane protectionSummary = new GridPane();
        protectionSummary.getStyleClass().add("protection-summary");
        protectionSummary.setHgap(Ui.SPACE_4);
        protectionSummary.setVgap(Ui.SPACE_4);
        configureProtectionSummary(protectionSummary, profileSection, passSummary, false);
        boolean[] summaryStacked = {false};
        protectionSummary.widthProperty().addListener((obs, old, width) -> {
            boolean narrow = width.doubleValue() < 720;
            if (narrow == summaryStacked[0]) return;
            summaryStacked[0] = narrow;
            configureProtectionSummary(protectionSummary, profileSection, passSummary, narrow);
        });

        Button reset = Ui.button("Reset to recommended", "secondary-button", () -> {
            resetRecommended(descriptor);
            transformerList.refresh();
        });
        Label settingsCopy = Ui.label(descriptor.name().equals("frostjni")
                        ? "Choose compilers, conversion targets, and native output behavior."
                        : "Recommended controls stay visible; high-risk bounds remain under Advanced settings.",
                "settings-description");
        settingsCopy.setWrapText(true);
        VBox settingsHeadingCopy = new VBox(Ui.SPACE_1,
                Ui.label("Transformer settings", "section-title"), settingsCopy);
        settingsHeadingCopy.setMinWidth(0);
        HBox.setHgrow(settingsHeadingCopy, Priority.ALWAYS);
        HBox settingsHeading = new HBox(Ui.SPACE_6, settingsHeadingCopy, reset);
        settingsHeading.setAlignment(Pos.CENTER_LEFT);
        settingsHeading.setMinWidth(0);

        Node settings = descriptor.name().equals("frostjni") ? nativeSettings()
                : renderer.render(context.projectState(), descriptor.name());
        if (settings instanceof VBox box) box.getStyleClass().add("settings-renderer");
        VBox settingsRegion = new VBox(Ui.SPACE_6, settingsHeading, settings);
        settingsRegion.getStyleClass().add("transformer-settings-region");
        editor.getChildren().addAll(protectionSummary, header, settingsRegion);
        if (root.getWidth() < 1320 && root.getWidth() > 0) {
            if (workspace.getChildren().contains(right)) {
                workspace.getChildren().remove(right);
            }
            if (!editor.getChildren().contains(right)) {
                editor.getChildren().add(right);
            }
            right.setMinWidth(0);
            right.setMaxWidth(Double.MAX_VALUE);
        }
        refreshEnabledPassCount();
        refreshImpact();
        if (!initializing) motion.pageIn(editor);
    }

    private VBox enabledPassCard() {
        enabledPassValue = Ui.label("", "enabled-pass-value");
        enabledPassProgress = new ProgressBar();
        enabledPassProgress.setMaxWidth(Double.MAX_VALUE);
        enabledPassProgress.getStyleClass().add("enabled-pass-progress");
        Label description = Ui.label("Active in the current project configuration.", "enabled-pass-description");
        description.setWrapText(true);
        VBox card = new VBox(Ui.SPACE_3,
                Ui.label("Enabled passes", "profile-title"),
                enabledPassValue,
                enabledPassProgress,
                description);
        card.getStyleClass().add("enabled-pass-card");
        card.setMinWidth(190);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private void refreshEnabledPassCount() {
        if (enabledPassValue == null || enabledPassProgress == null) return;
        long enabled = catalog.all().stream().filter(this::enabled).count();
        int total = catalog.all().size();
        enabledPassValue.setText(enabled + " of " + total + " passes");
        enabledPassProgress.setProgress(total == 0 ? 0 : (double) enabled / total);
        enabledPassValue.setAccessibleText(enabled + " of " + total + " protection passes enabled");
    }

    private static void configureProtectionSummary(GridPane grid, VBox profile, VBox passSummary,
                                                     boolean stacked) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        if (stacked) {
            ColumnConstraints full = new ColumnConstraints();
            full.setPercentWidth(100);
            full.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(full);
            grid.add(profile, 0, 0);
            grid.add(passSummary, 0, 1);
        } else {
            ColumnConstraints profileColumn = new ColumnConstraints();
            profileColumn.setPercentWidth(68);
            profileColumn.setHgrow(Priority.ALWAYS);
            ColumnConstraints summaryColumn = new ColumnConstraints();
            summaryColumn.setPercentWidth(32);
            summaryColumn.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(profileColumn, summaryColumn);
            grid.add(profile, 0, 0);
            grid.add(passSummary, 1, 0);
        }
        GridPane.setHgrow(profile, Priority.ALWAYS);
        GridPane.setHgrow(passSummary, Priority.ALWAYS);
        GridPane.setMargin(profile, Insets.EMPTY);
        GridPane.setMargin(passSummary, Insets.EMPTY);
    }

    private void resetRecommended(TransformerCatalog.Descriptor descriptor) {
        if (descriptor.name().equals("frostjni")) {
            resetFrostJni();
        } else {
            TransformerConfig recommended = ProtectionProfiles.recommended(
                    "Custom".equals(context.projectState().profileProperty().get()) ? "Balanced"
                            : context.projectState().profileProperty().get(), descriptor.name());
            context.projectState().configuration().getTransformers().put(descriptor.name(), recommended);
        }
        context.projectState().touch();
        select(descriptor);
    }

    private void resetFrostJni() {
        FrostJNIConfig target = context.projectState().configuration().getFrostJNI();
        FrostJNIConfig defaults = new FrostJNIConfig();
        target.setOutputLibraryName(defaults.getOutputLibraryName());
        target.setWindowsDllName(defaults.getWindowsDllName());
        target.setLinuxSoName(defaults.getLinuxSoName());
        target.setMacDylibName(defaults.getMacDylibName());
        target.setUseGcc(defaults.isUseGcc());
        target.setUseClang(defaults.isUseClang());
        target.setUseMsvc(defaults.isUseMsvc());
        target.setUseZig(defaults.isUseZig());
        target.setMode(defaults.getMode());
        target.setCompileMode(defaults.getCompileMode());
        target.setUnityBuild(defaults.isUnityBuild());
        target.setOptimizationLevel(defaults.getOptimizationLevel());
        target.setStripSymbols(defaults.isStripSymbols());
        target.setCompressLibrary(defaults.isCompressLibrary());
        target.setGenerateHeaders(defaults.isGenerateHeaders());
        target.setIncludeClasses(defaults.getIncludeClasses());
        target.setIncludePackages(defaults.getIncludePackages());
        target.setIncludeMethods(defaults.getIncludeMethods());
        target.setIncludeAnnotations(defaults.getIncludeAnnotations());
        target.setExcludedClasses(defaults.getExcludedClasses());
        target.setExcludedPackages(defaults.getExcludedPackages());
        target.setExcludedAnnotations(defaults.getExcludedAnnotations());
        target.setTemporaryDirectory(defaults.getTemporaryDirectory());
        target.setKeepGeneratedSources(defaults.isKeepGeneratedSources());
        target.setLoaderMode(defaults.getLoaderMode());
        target.setResourceEmbedding(defaults.isResourceEmbedding());
        target.setDebugMode(defaults.isDebugMode());
        target.setFailFast(defaults.isFailFast());
        target.setContinueOnFailure(defaults.isContinueOnFailure());
    }

    private Node nativeSettings() {
        FrostJNIConfig nativeConfig = context.projectState().configuration().getFrostJNI();
        TextField library = text(nativeConfig.getOutputLibraryName());
        TextField temp = text(nativeConfig.getTemporaryDirectory());
        CustomComboBox<String> mode = new CustomComboBox<>(List.of("SELECTIVE", "FULL"));
        mode.setValue(nativeConfig.getMode());
        CustomComboBox<String> compile = new CustomComboBox<>(List.of("FAST", "RELEASE"));
        compile.setValue(nativeConfig.getCompileMode());
        CustomComboBox<String> optimization = new CustomComboBox<>(List.of("O0", "O1", "O2", "O3"));
        optimization.setValue(nativeConfig.getOptimizationLevel());
        CheckBox clang = check("Use Clang", nativeConfig.isUseClang());
        CheckBox gcc = check("Use GCC / MinGW", nativeConfig.isUseGcc());
        CheckBox msvc = check("Use MSVC Build Tools", nativeConfig.isUseMsvc());
        CheckBox zig = check("Use Zig", nativeConfig.isUseZig());
        CheckBox strip = check("Strip native symbols", nativeConfig.isStripSymbols());
        CheckBox unity = check("Use a unity build", nativeConfig.isUnityBuild());
        CheckBox keepSources = check("Keep generated sources", nativeConfig.isKeepGeneratedSources());
        CheckBox embed = check("Embed native libraries in the output JAR", nativeConfig.isResourceEmbedding());
        CheckBox failFast = check("Fail the build on native errors", nativeConfig.isFailFast());
        CheckBox continueOnFailure = check("Continue with Java output after native failure", nativeConfig.isContinueOnFailure());

        TextArea includePackages = area(String.join("\n", nativeConfig.getIncludePackages()), "com.example.security");
        TextArea includeClasses = area(String.join("\n", nativeConfig.getIncludeClasses()), "com.example.LicenseManager");
        TextArea includeMethods = area(String.join("\n", nativeConfig.getIncludeMethods()), "com.example.Class#method");
        TextArea exclusions = area(String.join("\n", nativeConfig.getExcludedClasses()), "com.example.generated.*");

        Runnable sync = () -> {
            nativeConfig.setOutputLibraryName(library.getText().trim());
            nativeConfig.setTemporaryDirectory(temp.getText().trim());
            nativeConfig.setMode(mode.getValue());
            nativeConfig.setCompileMode(compile.getValue());
            nativeConfig.setOptimizationLevel(optimization.getValue());
            nativeConfig.setUseClang(clang.isSelected());
            nativeConfig.setUseGcc(gcc.isSelected());
            nativeConfig.setUseMsvc(msvc.isSelected());
            nativeConfig.setUseZig(zig.isSelected());
            nativeConfig.setStripSymbols(strip.isSelected());
            nativeConfig.setUnityBuild(unity.isSelected());
            nativeConfig.setKeepGeneratedSources(keepSources.isSelected());
            nativeConfig.setResourceEmbedding(embed.isSelected());
            nativeConfig.setFailFast(failFast.isSelected());
            nativeConfig.setContinueOnFailure(continueOnFailure.isSelected());
            nativeConfig.setIncludePackages(lines(includePackages));
            nativeConfig.setIncludeClasses(lines(includeClasses));
            nativeConfig.setIncludeMethods(lines(includeMethods));
            nativeConfig.setExcludedClasses(lines(exclusions));
            context.projectState().touch();
        };
        for (TextInputControl control : List.of(library, temp, includePackages, includeClasses, includeMethods, exclusions)) {
            control.textProperty().addListener((obs, old, value) -> sync.run());
        }
        for (CheckBox box : List.of(clang, gcc, msvc, zig, strip, unity, keepSources, embed, failFast, continueOnFailure)) {
            box.selectedProperty().addListener((obs, old, value) -> sync.run());
        }
        mode.valueProperty().addListener((obs, old, value) -> sync.run());
        compile.valueProperty().addListener((obs, old, value) -> sync.run());
        optimization.valueProperty().addListener((obs, old, value) -> sync.run());

        FlowPane compilerChoices = new FlowPane(Ui.SPACE_4, Ui.SPACE_3, clang, gcc, msvc, zig);
        VBox detectedToolchains = new VBox(Ui.SPACE_2,
                Ui.label("Scanning local compiler installations…", "section-description"));
        Button rescanToolchains = Ui.button("Rescan toolchains", "secondary-button", () -> { });
        rescanToolchains.setOnAction(event -> detectToolchains(detectedToolchains, rescanToolchains));
        detectToolchains(detectedToolchains, rescanToolchains);
        VBox compilers = Ui.section("Compiler", "Choose the native compiler families FrostJNI may use.",
                compilerChoices, detectedToolchains, rescanToolchains, Ui.fieldRow("Compile mode", compile),
                Ui.fieldRow("Optimization", optimization), strip, unity);
        VBox selection = Ui.section("Native selection", "Selective mode converts only the listed targets.",
                Ui.fieldRow("Mode", mode), Ui.fieldRow("Packages", includePackages),
                Ui.fieldRow("Classes", includeClasses), Ui.fieldRow("Methods", includeMethods),
                Ui.fieldRow("Excluded classes", exclusions));
        VBox output = Ui.section("Output & failure behavior", "Generated libraries are platform-specific.",
                Ui.fieldRow("Library name", library), Ui.fieldRow("Work directory", temp),
                keepSources, embed, failFast, continueOnFailure);
        return new VBox(Ui.SPACE_8, compilers, selection, output);
    }

    private void detectToolchains(VBox target, Button trigger) {
        if (trigger != null) trigger.setDisable(true);
        target.getChildren().setAll(Ui.label("Scanning local compiler installations…", "section-description"));
        context.nativeToolchainService().detect().whenComplete((compilers, failure) -> Platform.runLater(() -> {
            if (trigger != null) trigger.setDisable(false);
            target.getChildren().clear();
            if (failure != null) {
                target.getChildren().add(Ui.label("Toolchain detection failed: " + failure.getMessage(), "validation-error"));
                return;
            }
            if (compilers.isEmpty()) {
                target.getChildren().add(Ui.label(
                        "No supported compiler was found. Install Clang, GCC/MinGW, MSVC Build Tools, or Zig.",
                        "section-description"));
                return;
            }
            for (DetectedCompiler compiler : compilers) {
                Label path = Ui.label(compiler.executable().toString(), "section-description");
                path.setWrapText(true);
                String version = compiler.version().isBlank() ? compiler.kind().name() : compiler.version();
                VBox details = new VBox(Ui.SPACE_1, Ui.label(version, "info-value"), path);
                HBox row = new HBox(Ui.SPACE_3, new StatusChip(compiler.displayName(), "success"), details);
                row.setAlignment(Pos.CENTER_LEFT);
                target.getChildren().add(row);
            }
        }));
    }

    private void refreshImpact() {
        impactPanel.getChildren().clear();
        if (selected == null) return;
        impactPanel.getChildren().addAll(
                new StatusChip(selected.compatibility(), selected.compatibility().startsWith("Generally") ? "success" : "warning"),
                metric("Build/runtime impact", selected.impact()),
                metric("Category", selected.category().display()),
                Ui.label("Why this matters", "impact-heading"),
                Ui.label(impactCopy(selected), "impact-copy")
        );
        ((Label) impactPanel.getChildren().getLast()).setWrapText(true);
        if (context.projectState().analysis().reflectionUsage()
                && (selected.name().contains("rename") || selected.name().contains("reflection"))) {
            impactPanel.getChildren().add(new StatusChip("Reflection detected", "warning"));
        }
    }

    private boolean enabled(TransformerCatalog.Descriptor descriptor) {
        if (descriptor.name().equals("frostjni")) return context.projectState().configuration().getFrostJNI().isEnabled();
        TransformerConfig config = context.projectState().configuration().getTransformerConfig(descriptor.name());
        return config != null && config.isEnabled();
    }

    private void setEnabled(TransformerCatalog.Descriptor descriptor, boolean enabled) {
        if (descriptor.name().equals("frostjni")) {
            context.projectState().configuration().getFrostJNI().setEnabled(enabled);
        } else {
            context.projectState().configuration().getTransformers()
                    .computeIfAbsent(descriptor.name(), key -> new TransformerConfig()).setEnabled(enabled);
        }
    }

    private static Node metric(String label, String value) {
        Label key = Ui.label(label, "field-label");
        Label data = Ui.label(value, "info-value");
        key.setWrapText(true);
        data.setWrapText(true);
        key.setMinWidth(0);
        data.setMinWidth(0);
        GridPane row = new GridPane();
        row.setHgap(Ui.SPACE_3);
        ColumnConstraints keyColumn = new ColumnConstraints();
        keyColumn.setPercentWidth(58);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setPercentWidth(42);
        valueColumn.setHgrow(Priority.ALWAYS);
        row.getColumnConstraints().addAll(keyColumn, valueColumn);
        row.add(key, 0, 0);
        row.add(data, 1, 0);
        return row;
    }

    private static String impactCopy(TransformerCatalog.Descriptor descriptor) {
        if (descriptor.name().equals("frostjni")) {
            return "Native conversion increases build complexity and creates platform-specific output. Begin with a small selective target set.";
        }
        if (descriptor.impact().equals("High")) {
            return "Use the recommended bounds first. Higher values can increase output size, runtime cost, and build time quickly.";
        }
        return "Recommended values balance protection with compatibility. Advanced bounds remain available when you need precise control.";
    }

    private static TextField text(String value) {
        TextField field = new TextField(value == null ? "" : value);
        field.getStyleClass().add("text-input");
        return field;
    }

    private static TextArea area(String value, String prompt) {
        TextArea area = new TextArea(value);
        area.getStyleClass().add("text-area");
        area.setPromptText(prompt);
        area.setPrefRowCount(2);
        return area;
    }

    private static CheckBox check(String text, boolean selected) {
        CheckBox box = new CheckBox(text);
        box.setSelected(selected);
        return box;
    }

    private static List<String> lines(TextArea area) {
        return area.getText().lines().map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private TransformerCatalog.Category savedCategory() {
        try {
            return TransformerCatalog.Category.valueOf(
                    context.preferences().get("protection.category",
                            TransformerCatalog.Category.RENAMING.name()));
        } catch (IllegalArgumentException ignored) {
            return TransformerCatalog.Category.RENAMING;
        }
    }

    @Override
    public Node root() { return root; }
}
