package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.export.ExportOptions;
import dev.frost.obfuscator.gui.export.ProjectInventory;
import dev.frost.obfuscator.gui.stringexport.StringCategory;
import dev.frost.obfuscator.gui.stringexport.StringRecord;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.gui.viewer.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.stage.FileChooser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BytecodeViewerPage implements PageView {
    private static final KeyCombination FIND = new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN);
    private final AppContext context;
    private final Consumer<PageId> navigate;
    private final BytecodeViewerService service;
    private final BorderPane root = new BorderPane();
    private final VBox emptyState = new VBox(Ui.SPACE_4);
    private final SplitPane workspace = new SplitPane();

    // ... existing fields ...

    private final TreeView<ArchiveNode> hierarchy = new TreeView<>();
    private final TextField archiveSearch = new TextField();
    private final TextField sourceSearch = new TextField();
    private final CheckBox matchCase = new CheckBox("Match case");
    private final CodeArea source = codeArea("viewer-source");
    private final CodeArea bytecode = codeArea("viewer-bytecode");
    private final VBox hexTableContent = new VBox();
    private final ScrollPane hexTableScroll = new ScrollPane(hexTableContent);
    private final VBox constantPoolContent = new VBox();
    private final ScrollPane constantPoolScroll = new ScrollPane(constantPoolContent);
    private final VBox sourceSkeleton = new VBox(Ui.SPACE_3);
    private final Label documentTitle = Ui.label("No class selected", "viewer-document-title");
    private final Label documentMeta = Ui.label("Select a class from the archive tree", "viewer-document-meta");
    private final Label status = Ui.label("Waiting for an analyzed input JAR", "viewer-status");
    private final Label archiveSummary = Ui.label("", "viewer-archive-summary");
    private final VBox overview = new VBox(Ui.SPACE_3);
    private final ListView<String> members = list("No fields or methods in this class.");
    private final ListView<ArchiveInspector.StringOccurrence> strings = new ListView<>();
    private final ListView<ArchiveInspector.CallEdge> calls = new ListView<>();
    private final ListView<ArchiveInspector.CapabilityFinding> findings = new ListView<>();
    private final TabPane editorTabs = new TabPane();
    private final TabPane inspectorTabs = new TabPane();
    private final StackPane inspectorContent = new StackPane();
    private final VBox toolDrawer = new VBox(Ui.SPACE_3);
    private final ToggleButton archiveToggle = new ToggleButton("Archive");
    private final ToggleButton inspectorToggle = new ToggleButton("Inspector");
    private final ToggleButton stringToggle = new ToggleButton("Strings");
    private final ToggleButton editModeToggle = new ToggleButton("Edit Mode");
    private final Button assembleBtn = Ui.button("Apply changes", "primary-button", this::assembleAndApplyActiveEditor);
    private final Button saveJarBtn = Ui.button("Save copy…", "secondary-button", this::openSaveJarDialog);
    private final VBox errorDrawer = new VBox(4);
    private final ListView<InJarJavaCompiler.CompilationDiagnostic> errorList = new ListView<>();
    private final IdeCompletionService completionService = new IdeCompletionService();
    private final EditorCompletionPopup completionPopup = new EditorCompletionPopup();
    private VBox stringWorkbenchPanel;
    private final VBox stringWorkbenchContent = new VBox(6);
    private List<StringRecord> cachedRecords;
    private final CustomComboBox<DecompilerBackend> decompiler;
    private final Button refresh = Ui.button("Refresh", "secondary-button", () -> loadSelectedClass(true));
    private final PauseTransition treeSearchDelay = new PauseTransition(Duration.millis(160));
    private final AtomicLong operation = new AtomicLong();
    private final Map<Path, ArchiveInspector.ArchiveSnapshot> loadedSnapshots = new LinkedHashMap<>();
    private Path primaryArchive;
    private ArchiveInspector.ArchiveSnapshot snapshot;
    private ArchiveInspector.ClassInspection selectedInspection;
    private ArchiveNode selectedNode;
    private CompletableFuture<?> pendingInspect;
    private VBox archivePanel;
    private VBox editorPanel;
    private VBox inspectorPanel;
    private Path loadedArchive;
    private boolean active;
    private boolean refreshPending;

    public BytecodeViewerPage(AppContext context, Consumer<PageId> navigate) {
        this.context = context;
        this.navigate = navigate;
        this.service = context.bytecodeViewerService();
        this.decompiler = new CustomComboBox<>(service.backends(),
                backend -> backend.displayName() + " " + backend.version());
        build();
        context.projectState().analysisProperty().addListener((obs, old, value) -> requestRefresh());
        context.projectState().configurationProperty().addListener((obs, old, value) -> requestRefresh());
    }

    private void build() {
        root.getStyleClass().addAll("page", "viewer-page");
        root.setPadding(new Insets(Ui.SPACE_4, Ui.SPACE_4, Ui.SPACE_2, Ui.SPACE_4));

        Label title = Ui.label("Decompiler", "viewer-workbench-title");
        Label subtitle = Ui.label("Inspect, compare, and edit JVM archives", "viewer-workbench-subtitle");
        VBox identity = new VBox(2, title, subtitle);
        identity.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(Ui.SPACE_3, identity, Ui.spacer(), actionToolbar());
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("viewer-workbench-bar");
        BorderPane.setMargin(header, new Insets(0, 0, Ui.SPACE_3, 0));
        root.setTop(header);

        buildEmptyState();
        buildWorkspace();
        StackPane center = new StackPane(emptyState, workspace);
        root.setCenter(center);

        HBox statusBar = new HBox(Ui.SPACE_3, status, Ui.spacer(), archiveSummary);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("viewer-status-bar");
        BorderPane.setMargin(statusBar, new Insets(Ui.SPACE_2, 0, 0, 0));
        root.setBottom(statusBar);

        archiveSearch.textProperty().addListener((obs, old, value) -> treeSearchDelay.playFromStart());
        treeSearchDelay.setOnFinished(event -> rebuildTree());
        decompiler.valueProperty().addListener((obs, old, value) -> {
            if (selectedNode != null && selectedNode.kind == NodeKind.CLASS) loadSelectedClass(false);
        });
        sourceSearch.setOnAction(event -> findNext());
        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (FIND.match(event)) {
                sourceSearch.requestFocus();
                sourceSearch.selectAll();
                event.consume();
            }
        });
        showArchive(null);
    }

    private Node actionToolbar() {
        archiveToggle.setSelected(true);
        inspectorToggle.setSelected(false);
        stringToggle.setSelected(false);
        archiveToggle.getStyleClass().add("viewer-rail-toggle");
        inspectorToggle.getStyleClass().add("viewer-rail-toggle");
        stringToggle.getStyleClass().add("viewer-rail-toggle");
        archiveToggle.setGraphic(new FontIcon("fth-archive"));
        inspectorToggle.setGraphic(new FontIcon("fth-info"));
        stringToggle.setGraphic(new FontIcon("fth-type"));

        archiveToggle.setOnAction(event -> setArchiveVisible(archiveToggle.isSelected()));
        inspectorToggle.setOnAction(event -> setInspectorVisible(inspectorToggle.isSelected()));
        stringToggle.setOnAction(event -> setStringWorkbenchVisible(stringToggle.isSelected()));

        HBox segmentedToggles = new HBox(4, archiveToggle, inspectorToggle, stringToggle);
        segmentedToggles.getStyleClass().add("viewer-segmented-bar");
        segmentedToggles.setAlignment(Pos.CENTER_LEFT);

        MenuItem manifest = menuItem("View manifest", this::showManifest);
        MenuItem mains = menuItem("Show main methods", () -> showScanResult(ScanView.MAINS));
        MenuItem allStrings = menuItem("Show string constants", () -> showScanResult(ScanView.STRINGS));
        MenuItem exportStringsWorkbench = menuItem("Export All Strings…", this::showStringExportWorkbench);
        MenuItem flow = menuItem("Code call sequence", () -> showScanResult(ScanView.CALLS));
        MenuItem scan = menuItem("Capability scanner", () -> showScanResult(ScanView.FINDINGS));
        MenuButton explore = new MenuButton("Explore");
        explore.setGraphic(new FontIcon("fth-compass"));
        explore.getItems().addAll(manifest, mains, allStrings, exportStringsWorkbench, flow, scan);
        explore.getStyleClass().add("inline-button");
        explore.setMinWidth(Region.USE_PREF_SIZE);
        dev.frost.obfuscator.gui.component.MenuAnimation.setup(explore);

        // 1. Decompiled sources
        Menu sourcesSubmenu = new Menu("Decompiled sources");
        sourcesSubmenu.getItems().addAll(
                menuItem("Export selected class as .java…", this::exportSelectedClassJava),
                menuItem("Export selected package…", this::exportSelectedPackageSources),
                menuItem("Export complete project…", this::exportCompleteProjectSources),
                menuItem("Export as source ZIP…", this::exportSourceZip),
                new SeparatorMenuItem(),
                menuItem("Export separate output from all decompilers…", this::exportAllDecompilers),
                menuItem("Export decompiler comparison report…", this::exportComparisonReport)
        );

        // 2. Raw bytecode & disassembly
        Menu bytecodeSubmenu = new Menu("Raw bytecode & disassembly");
        bytecodeSubmenu.getItems().addAll(
                menuItem("Export selected class as .class…", this::exportSelectedClassBytecode),
                menuItem("Export package bytecode…", this::exportPackageBytecode),
                menuItem("Export all classes…", this::exportAllClassesBytecode),
                new SeparatorMenuItem(),
                menuItem("Export ASM Textifier output (.bytecode.asm)…", this::exportAsmTextifier),
                menuItem("Export JVM instruction javap -v listing…", this::exportJavapDisassembly),
                menuItem("Export control-flow graph (Mermaid & DOT)…", this::exportControlFlowGraph),
                menuItem("Export method bytecode only…", this::exportMethodBytecodeOnly)
        );

        // 3. Project & archive exports
        Menu projectSubmenu = new Menu("Project & archive exports");
        projectSubmenu.getItems().addAll(
                menuItem("Export original resources & META-INF…", this::exportResourcesAndMeta),
                menuItem("Export project inventory JSON…", this::exportProjectInventoryJson),
                new SeparatorMenuItem(),
                menuItem("Rebuild sanitized JAR…", this::rebuildSanitizedJar)
        );

        MenuItem exportStringsItem = menuItem("Export All Strings…", this::showStringExportWorkbench);
        MenuItem replace = menuItem("Replace exact strings in a copy…", this::showReplaceTool);
        MenuItem frames = menuItem("Strip stack-map frames from a copy…", this::showFramesTool);
        MenuItem versions = menuItem("Change class-file versions in a copy…", this::showVersionTool);

        MenuButton tools = new MenuButton("Export");
        tools.setGraphic(new FontIcon("fth-package"));
        tools.getItems().addAll(
                exportStringsItem,
                new SeparatorMenuItem(),
                sourcesSubmenu,
                bytecodeSubmenu,
                projectSubmenu,
                new SeparatorMenuItem(),
                replace,
                frames,
                versions
        );
        tools.getStyleClass().add("inline-button");
        tools.setMinWidth(Region.USE_PREF_SIZE);
        dev.frost.obfuscator.gui.component.MenuAnimation.setup(tools);

        HBox actions = new HBox(Ui.SPACE_3, segmentedToggles, explore, tools);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("viewer-workbench-actions");
        return actions;
    }

    private static MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private void buildEmptyState() {
        FontIcon icon = new FontIcon("fth-code");
        icon.getStyleClass().add("viewer-empty-icon");
        StackPane iconMark = new StackPane(icon);
        iconMark.getStyleClass().add("viewer-empty-mark");

        Label title = Ui.label("Open a JAR. See what is inside.", "empty-state-title", "viewer-empty-title");
        Label copy = Ui.label("Recover readable source, inspect raw bytecode, and compare decompiler output "
                + "without modifying the original archive.", "empty-state-copy", "viewer-empty-copy");
        copy.setWrapText(true);
        copy.setMaxWidth(520);

        HBox workflow = new HBox(Ui.SPACE_2,
                emptyStep("1", "Open archive"), emptyArrow(),
                emptyStep("2", "Choose a class"), emptyArrow(),
                emptyStep("3", "Inspect output"));
        workflow.setAlignment(Pos.CENTER);
        workflow.getStyleClass().add("viewer-empty-workflow");

        Button openJar = Ui.button("Open JAR or ZIP…", "primary-button", this::openAddExtraJarDialog);
        openJar.setGraphic(new FontIcon("fth-folder-plus"));
        Button choose = Ui.button("Use project input", "secondary-button", () -> navigate.accept(PageId.INPUT));
        choose.setGraphic(new FontIcon("fth-arrow-right"));
        HBox emptyButtons = new HBox(Ui.SPACE_2, openJar, choose);
        emptyButtons.setAlignment(Pos.CENTER);
        emptyState.getChildren().addAll(iconMark, title, copy, workflow, emptyButtons);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.getStyleClass().add("viewer-empty-state");
        emptyState.setMaxWidth(680);
        emptyState.setMaxHeight(Region.USE_PREF_SIZE);
    }

    private void buildWorkspace() {
        workspace.getStyleClass().add("viewer-workspace");

        archivePanel = new VBox(Ui.SPACE_2);
        archivePanel.getStyleClass().add("viewer-archive-panel");
        archivePanel.setMinWidth(100);
        archivePanel.setPrefWidth(220);
        archivePanel.setMaxWidth(Double.MAX_VALUE);
        archiveSearch.setPromptText("Filter archive");
        archiveSearch.getStyleClass().addAll("text-input", "viewer-search");
        hierarchy.setShowRoot(true);
        hierarchy.getStyleClass().add("viewer-tree");
        hierarchy.setCellFactory(tree -> new ArchiveTreeCell());
        hierarchy.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (value != null) select(value.getValue());
        });
        VBox.setVgrow(hierarchy, Priority.ALWAYS);
        Button addJarBtn = compactButton("Add JAR…", "fth-plus", this::openAddExtraJarDialog);
        FontIcon archiveIcon = new FontIcon("fth-archive");
        archiveIcon.getStyleClass().add("viewer-panel-icon");
        HBox archiveHeaderRow = new HBox(Ui.SPACE_2, archiveIcon,
                Ui.label("Archive", "viewer-panel-title"), Ui.spacer(), addJarBtn);
        archiveHeaderRow.setAlignment(Pos.CENTER_LEFT);
        archivePanel.getChildren().addAll(archiveHeaderRow, archiveSearch, hierarchy);

        editorPanel = (VBox) sourcePane();
        editorPanel.setMinWidth(120);
        editorPanel.setMaxWidth(Double.MAX_VALUE);

        inspectorPanel = (VBox) inspectorPane();
        inspectorPanel.setMinWidth(120);
        inspectorPanel.setPrefWidth(300);
        inspectorPanel.setMaxWidth(Double.MAX_VALUE);

        stringWorkbenchPanel = buildStringWorkbenchPanel();

        archiveToggle.setSelected(true);
        inspectorToggle.setSelected(false);
        stringToggle.setSelected(false);

        updateWorkspacePanels();
    }

    private Node sourcePane() {
        refresh.setGraphic(new FontIcon("fth-refresh-cw"));
        Button next = compactButton("Next", "fth-arrow-down", this::findNext);
        Button copy = compactButton("Copy", "fth-copy", this::copySource);
        CheckMenuItem match = new CheckMenuItem("Match case");
        match.selectedProperty().bindBidirectional(matchCase.selectedProperty());
        MenuButton findOptions = new MenuButton("Options");
        findOptions.setGraphic(new FontIcon("fth-sliders"));
        findOptions.getItems().add(match);
        findOptions.getStyleClass().add("inline-button");
        dev.frost.obfuscator.gui.component.MenuAnimation.setup(findOptions);

        refresh.getStyleClass().remove("secondary-button");
        refresh.getStyleClass().add("viewer-action");
        makeIconOnly(refresh, "Refresh decompiled source");
        makeIconOnly(copy, "Copy source");
        makeIconOnly(next, "Find next match");
        makeIconOnly(findOptions, "Search options");

        decompiler.setMinWidth(164);
        decompiler.setPrefWidth(188);
        decompiler.setMaxWidth(224);
        decompiler.setTooltip(new Tooltip("Decompiler engine"));
        sourceSearch.setPromptText("Find in source…");
        sourceSearch.getStyleClass().add("viewer-source-search");

        editModeToggle.setGraphic(new FontIcon("fth-edit-3"));
        editModeToggle.getStyleClass().add("viewer-rail-toggle");
        editModeToggle.setSelected(false);
        assembleBtn.setGraphic(new FontIcon("fth-zap"));
        assembleBtn.setVisible(false);
        assembleBtn.setManaged(false);
        saveJarBtn.setGraphic(new FontIcon("fth-download"));
        saveJarBtn.setVisible(false);
        saveJarBtn.setManaged(false);

        editModeToggle.selectedProperty().addListener((obs, old, isEdit) -> {
            source.setEditable(isEdit);
            bytecode.setEditable(isEdit);
            assembleBtn.setVisible(isEdit);
            assembleBtn.setManaged(isEdit);
            saveJarBtn.setVisible(isEdit);
            saveJarBtn.setManaged(isEdit);
            if (isEdit) {
                status.setText("Edit Mode Active — modify Source or Bytecode, then apply changes (Ctrl+S)");
            } else {
                status.setText("Edit Mode Disabled — read-only view");
                hideErrorDrawer();
            }
        });

        HBox decompilerGroup = new HBox(6, decompiler, refresh, copy);
        decompilerGroup.setAlignment(Pos.CENTER_LEFT);
        decompilerGroup.getStyleClass().add("viewer-toolbar-group");

        HBox searchGroup = new HBox(6, sourceSearch, next, findOptions);
        searchGroup.setAlignment(Pos.CENTER_LEFT);
        searchGroup.getStyleClass().add("viewer-toolbar-group");
        searchGroup.setMinWidth(164);
        HBox.setHgrow(sourceSearch, Priority.ALWAYS);

        HBox editGroup = new HBox(6, editModeToggle, assembleBtn, saveJarBtn);
        editGroup.setAlignment(Pos.CENTER_LEFT);
        editGroup.getStyleClass().add("viewer-edit-group");

        HBox toolbar = new HBox(8, decompilerGroup, searchGroup, editGroup);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("viewer-editor-toolbar");
        HBox.setHgrow(searchGroup, Priority.ALWAYS);

        VBox title = new VBox(Ui.SPACE_1, documentTitle, documentMeta);
        title.getStyleClass().add("viewer-document-header");
        source.setParagraphGraphicFactory(LineNumberFactory.get(source));
        source.setEditable(false);
        source.setWrapText(false);
        bytecode.setParagraphGraphicFactory(LineNumberFactory.get(bytecode));
        bytecode.setEditable(false);

        hexTableScroll.setFitToWidth(true);
        hexTableScroll.getStyleClass().add("hex-table-scroll");
        hexTableContent.getStyleClass().add("hex-table-container");
        constantPoolScroll.setFitToWidth(true);
        constantPoolScroll.getStyleClass().add("cp-table-scroll");
        constantPoolContent.getStyleClass().add("cp-table-container");

        configureErrorDrawer();

        buildSkeleton();
        StackPane editor = new StackPane(source, sourceSkeleton);
        editor.getStyleClass().add("viewer-editor-stack");
        editorTabs.getStyleClass().addAll("viewer-editor-tabs", "analytics-tabs");
        editorTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        editorTabs.getTabs().addAll(
                tab("Source", editor),
                tab("Bytecode", bytecode),
                tab("Hex Table", hexTableScroll),
                tab("Constant Pool", constantPoolScroll)
        );
        VBox.setVgrow(editorTabs, Priority.ALWAYS);

        source.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (editModeToggle.isSelected() && completionPopup.isShowing()) {
                if (completionPopup.handleKeyPress(event)) {
                    event.consume();
                }
            } else if (new KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN).match(event)) {
                if (editModeToggle.isSelected()) {
                    triggerAutocompletionOnType();
                    event.consume();
                }
            }
        });

        source.textProperty().addListener((obs, oldText, newText) -> {
            if (editModeToggle.isSelected()) {
                Platform.runLater(this::triggerAutocompletionOnType);
            }
        });

        VBox pane = new VBox(title, toolbar, editorTabs, errorDrawer);
        pane.getStyleClass().add("viewer-source-pane");

        pane.setOnKeyPressed(event -> {
            if (new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN).match(event)) {
                if (editModeToggle.isSelected()) {
                    assembleAndApplyActiveEditor();
                    event.consume();
                }
            }
        });

        return pane;
    }

    private Node inspectorPane() {
        configureInspectorLists();
        inspectorTabs.getStyleClass().addAll("analytics-tabs", "viewer-inspector-tabs");
        inspectorTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        inspectorTabs.getTabs().addAll(
                tab("Info", scroll(overview)),
                tab("Members", members),
                tab("Strings", strings),
                tab("Calls", calls),
                tab("Scan", findings)
        );
        toolDrawer.getStyleClass().add("viewer-tool-drawer");
        toolDrawer.setVisible(false);
        toolDrawer.setManaged(false);
        inspectorContent.getChildren().addAll(inspectorTabs, toolDrawer);
        VBox pane = new VBox(Ui.SPACE_2, Ui.label("Inspector", "viewer-panel-title"), inspectorContent);
        pane.getStyleClass().add("viewer-inspector");
        VBox.setVgrow(inspectorContent, Priority.ALWAYS);
        return pane;
    }

    private VBox buildStringWorkbenchPanel() {
        Label heading = Ui.label("String Analysis", "viewer-panel-title");
        Button closeBtn = compactButton("✕ Close", () -> setStringWorkbenchVisible(false));
        closeBtn.setStyle("-fx-font-size: 0.85em; -fx-text-fill: -fx-text-muted;");
        HBox headerRow = new HBox(4, heading, Ui.spacer(), closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(6, headerRow, stringWorkbenchContent);
        panel.setPadding(new Insets(8));
        panel.getStyleClass().add("viewer-string-workbench");
        panel.setMinWidth(130);
        panel.setPrefWidth(360);
        panel.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(stringWorkbenchContent, Priority.ALWAYS);
        return panel;
    }

    private void setArchiveVisible(boolean visible) {
        archiveToggle.setSelected(visible);
        updateWorkspacePanels();
    }

    private void setInspectorVisible(boolean visible) {
        inspectorToggle.setSelected(visible);
        if (!visible) closeToolDrawer();
        updateWorkspacePanels();
    }

    private void setStringWorkbenchVisible(boolean visible) {
        stringToggle.setSelected(visible);
        if (visible && (cachedRecords == null || cachedRecords.isEmpty())) {
            showStringExportWorkbench();
        } else {
            updateWorkspacePanels();
        }
    }

    private void updateWorkspacePanels() {
        if (workspace == null) return;
        boolean archiveVis = archiveToggle.isSelected();
        boolean inspectorVis = inspectorToggle.isSelected();
        boolean stringVis = stringToggle.isSelected();

        List<Node> activeItems = new ArrayList<>();
        if (archiveVis) activeItems.add(archivePanel);
        activeItems.add(editorPanel); // Always in middle
        if (inspectorVis) activeItems.add(inspectorPanel);
        if (stringVis) activeItems.add(stringWorkbenchPanel);

        for (Node n : activeItems) {
            n.setVisible(true);
            n.setManaged(true);
        }

        workspace.getItems().setAll(activeItems);

        Platform.runLater(() -> {
            int count = activeItems.size();
            if (count == 2) {
                if (archiveVis) workspace.setDividerPositions(0.20);
                else workspace.setDividerPositions(0.55);
            } else if (count == 3) {
                if (archiveVis && inspectorVis && !stringVis) {
                    workspace.setDividerPositions(0.18, 0.70);
                } else if (archiveVis && !inspectorVis && stringVis) {
                    workspace.setDividerPositions(0.18, 0.50);
                } else if (!archiveVis && inspectorVis && stringVis) {
                    workspace.setDividerPositions(0.40, 0.70);
                }
            } else if (count == 4) {
                workspace.setDividerPositions(0.15, 0.42, 0.70);
            }
        });
    }

    private void configureInspectorLists() {
        members.getStyleClass().add("viewer-list");
        strings.getStyleClass().add("viewer-list");
        calls.getStyleClass().add("viewer-list");
        findings.getStyleClass().add("viewer-list");
        members.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setContextMenu(null);
                } else {
                    setText(item);
                    ContextMenu cm = new ContextMenu();
                    dev.frost.obfuscator.gui.component.MenuAnimation.setup(cm);
                    MenuItem deleteItem = new MenuItem("Delete Member");
                    deleteItem.setGraphic(new FontIcon("fth-trash-2"));
                    deleteItem.setOnAction(e -> deleteSelectedMember(item));
                    cm.getItems().add(deleteItem);
                    setContextMenu(cm);
                }
            }
        });
        strings.setPlaceholder(Ui.label("No string constants in this scope.", "empty-state-copy"));
        calls.setPlaceholder(Ui.label("No method calls in this scope.", "empty-state-copy"));
        findings.setPlaceholder(Ui.label("No noteworthy capabilities detected.", "empty-state-copy"));
        strings.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ArchiveInspector.StringOccurrence item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : stringRow(item));
            }
        });
        calls.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ArchiveInspector.CallEdge item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : callRow(item));
            }
        });
        findings.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ArchiveInspector.CapabilityFinding item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : findingRow(item));
            }
        });
    }

    private Path activeArchive() {
        if (selectedNode != null && selectedNode.archivePath != null) {
            return selectedNode.archivePath;
        }
        if (primaryArchive != null) {
            return primaryArchive;
        }
        if (!loadedSnapshots.isEmpty()) {
            return loadedSnapshots.keySet().iterator().next();
        }
        return null;
    }

    private void showArchive(Path archive) {
        if (archive == null) {
            primaryArchive = null;
            loadedArchive = null;
            snapshot = null;
            if (loadedSnapshots.isEmpty()) {
                emptyState.setVisible(true);
                emptyState.setManaged(true);
                workspace.setVisible(false);
                workspace.setManaged(false);
                archiveSummary.setText("");
                return;
            }
        } else {
            primaryArchive = archive.toAbsolutePath().normalize();
            loadedArchive = primaryArchive;
            status.setText("Indexing " + primaryArchive.getFileName() + "…");
            long token = operation.incrementAndGet();
            service.open(primaryArchive).whenComplete((value, error) -> Platform.runLater(() -> {
                if (token != operation.get()) return;
                if (error != null) {
                    fail("Could not open archive", error);
                    return;
                }
                snapshot = value;
                loadedSnapshots.put(primaryArchive, value);
                status.setText("Archive indexed");
                rebuildTree();
                openPendingGraphTarget();
            }));
        }
    }

    private void addExtraArchive(Path archive) {
        if (archive == null || !Files.isRegularFile(archive)) return;
        Path norm = archive.toAbsolutePath().normalize();
        if (loadedSnapshots.containsKey(norm)) {
            status.setText(norm.getFileName() + " is already loaded.");
            return;
        }
        status.setText("Indexing extra JAR " + norm.getFileName() + "…");
        service.open(norm).whenComplete((value, error) -> Platform.runLater(() -> {
            if (error != null) {
                fail("Could not open extra JAR " + norm.getFileName(), error);
                return;
            }
            loadedSnapshots.put(norm, value);
            if (primaryArchive == null) primaryArchive = norm;
            status.setText("Loaded " + norm.getFileName());
            rebuildTree();
        }));
    }

    private void removeArchive(Path archive) {
        if (archive == null) return;
        Path norm = archive.toAbsolutePath().normalize();
        loadedSnapshots.remove(norm);
        if (norm.equals(primaryArchive)) {
            primaryArchive = activeArchive();
            loadedArchive = primaryArchive;
            snapshot = primaryArchive != null ? loadedSnapshots.get(primaryArchive) : null;
        }
        if (selectedNode != null && norm.equals(selectedNode.archivePath)) {
            selectedNode = null;
            selectedInspection = null;
            documentTitle.setText("No class selected");
            documentMeta.setText("Select a class from the archive tree");
            source.replaceText("");
            bytecode.replaceText("");
            clearInspection();
        }
        status.setText("Removed " + norm.getFileName());
        rebuildTree();
    }

    private void openAddExtraJarDialog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Extra JAR / Zip File to Decompiler Workspace");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Java Archives (*.jar, *.zip)", "*.jar", "*.zip"),
                new FileChooser.ExtensionFilter("All Files (*.*)", "*.*")
        );
        Path initialDir = activeArchive();
        if (initialDir != null && initialDir.getParent() != null && Files.isDirectory(initialDir.getParent())) {
            chooser.setInitialDirectory(initialDir.getParent().toFile());
        }
        List<File> selectedFiles = chooser.showOpenMultipleDialog(root.getScene().getWindow());
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            for (File file : selectedFiles) {
                addExtraArchive(file.toPath());
            }
        }
    }

    private void rebuildTree() {
        if (loadedSnapshots.isEmpty()) {
            hierarchy.setRoot(null);
            emptyState.setVisible(true);
            emptyState.setManaged(true);
            workspace.setVisible(false);
            workspace.setManaged(false);
            archiveSummary.setText("");
            return;
        }

        emptyState.setVisible(false);
        emptyState.setManaged(false);
        workspace.setVisible(true);
        workspace.setManaged(true);

        String query = normalize(archiveSearch.getText());
        ArchiveNode workspaceRootNode = new ArchiveNode("Workspace", "", NodeKind.WORKSPACE, 0, 0, null);
        TreeItem<ArchiveNode> workspaceRootItem = new TreeItem<>(workspaceRootNode);

        long totalBytes = 0;
        int totalClasses = 0;
        int totalResources = 0;

        for (Map.Entry<Path, ArchiveInspector.ArchiveSnapshot> entry : loadedSnapshots.entrySet()) {
            Path archivePath = entry.getKey();
            ArchiveInspector.ArchiveSnapshot snap = entry.getValue();

            totalBytes += snap.size();
            totalClasses += snap.classCount();
            totalResources += snap.resourceCount();

            String jarName = archivePath.getFileName().toString();
            ArchiveNode jarNode = new ArchiveNode(jarName, "", NodeKind.ARCHIVE, snap.size(), 0, archivePath);
            TreeItem<ArchiveNode> jarItem = new TreeItem<>(jarNode);

            Map<String, TreeItem<ArchiveNode>> folders = new HashMap<>();
            folders.put("", jarItem);

            for (ArchiveInspector.ArchiveEntry e : snap.entries()) {
                if (!query.isBlank() && !e.name().toLowerCase(Locale.ROOT).contains(query)) continue;
                String[] parts = e.name().split("/");
                String parent = "";
                for (int index = 0; index < parts.length - 1; index++) {
                    String folderPath = parent.isEmpty() ? parts[index] : parent + "/" + parts[index];
                    if (!folders.containsKey(folderPath)) {
                        TreeItem<ArchiveNode> folder = new TreeItem<>(new ArchiveNode(parts[index],
                                folderPath, NodeKind.FOLDER, 0, 0, archivePath));
                        folders.get(parent).getChildren().add(folder);
                        folders.put(folderPath, folder);
                    }
                    parent = folderPath;
                }
                NodeKind kind = e.kind() == ArchiveInspector.EntryKind.CLASS
                        ? NodeKind.CLASS : NodeKind.RESOURCE;
                String name = parts[parts.length - 1];
                folders.get(parent).getChildren().add(new TreeItem<>(new ArchiveNode(name,
                        e.name(), kind, e.size(), e.classMajor(), archivePath)));
            }

            sortTree(jarItem);
            jarItem.setExpanded(true);
            workspaceRootItem.getChildren().add(jarItem);
        }

        if (loadedSnapshots.size() == 1) {
            TreeItem<ArchiveNode> singleJarItem = workspaceRootItem.getChildren().get(0);
            hierarchy.setRoot(singleJarItem);
            hierarchy.setShowRoot(true);
        } else {
            hierarchy.setRoot(workspaceRootItem);
            hierarchy.setShowRoot(false);
        }

        if (!query.isBlank()) {
            expandAll(hierarchy.getRoot());
        }

        archiveSummary.setText(loadedSnapshots.size() + " JAR" + (loadedSnapshots.size() > 1 ? "s" : "")
                + "  •  " + formatBytes(totalBytes) + "  •  " + totalClasses
                + " classes  •  " + totalResources + " resources");
    }

    private void select(ArchiveNode node) {
        operation.incrementAndGet();
        service.cancelActiveDecompilation();
        selectedNode = node;
        closeToolDrawer();
        if (node.kind == NodeKind.CLASS) {
            loadSelectedClass(false);
        } else if (node.kind == NodeKind.RESOURCE) {
            showResource(node);
        }
    }

    private void loadSelectedClass(boolean force) {
        indexWorkspaceSymbols();
        ArchiveNode node = selectedNode;
        DecompilerBackend backend = decompiler.getValue();
        Path archive = node != null && node.archivePath != null ? node.archivePath : activeArchive();
        if (archive == null || node == null || node.kind != NodeKind.CLASS || backend == null) return;
        long token = operation.incrementAndGet();
        service.cancelActiveDecompilation();
        if (pendingInspect != null) {
            pendingInspect.cancel(true);
            pendingInspect = null;
        }
        setLoading(true);
        documentTitle.setText(node.path.replace('/', '.').replaceFirst("\\.class$", ""));
        documentMeta.setText(backend.displayName() + " " + backend.version() + "  •  class major " + node.major);
        status.setText("Decompiling " + node.name + " with " + backend.displayName() + "…");

        service.decompile(backend, archive, node.path, force)
                .thenApply(result -> new StyledSource(result, JavaSyntax.highlight(result.source())))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (token != operation.get()) return;
                    if (error != null) {
                        if (error instanceof CancellationException
                                || (error.getCause() != null && error.getCause() instanceof CancellationException)) {
                            return;
                        }
                        setLoading(false);
                        source.replaceText("Decompilation failed.\n\n" + message(error));
                        source.clearStyle(0, source.getLength());
                        status.setText("Decompilation failed");
                        return;
                    }
                    source.replaceText(result.result.source());
                    source.setStyleSpans(0, result.styles);
                    source.moveTo(0);
                    setLoading(false);
                    status.setText("Decompiled in " + result.result.elapsed().toMillis() + " ms"
                            + (result.result.diagnostics().isEmpty() ? ""
                            : "  •  " + result.result.diagnostics().size() + " diagnostic(s)"));
                }));

        pendingInspect = service.inspect(archive, node.path);
        pendingInspect.whenComplete((inspection, error) ->
                Platform.runLater(() -> {
                    if (token != operation.get() || error != null) return;
                    selectedInspection = (ArchiveInspector.ClassInspection) inspection;
                    showInspection(selectedInspection);
                }));
    }

    private void showInspection(ArchiveInspector.ClassInspection value) {
        overview.getChildren().setAll(
                fact("Class", value.className()),
                fact("Kind", value.kind()),
                fact("Class version", value.classMajor() + "  •  Java " + javaVersion(value.classMajor())),
                fact("Access", blank(value.access(), "package-private")),
                fact("Extends", value.superClass()),
                fact("Implements", blank(String.join(", ", value.interfaces()), "None")),
                fact("Source file", blank(value.sourceFile(), "Not recorded")),
                fact("Generic signature", blank(value.signature(), "Not recorded")),
                fact("Annotations", blank(String.join(", ", value.annotations()), "None")),
                fact("Structure", value.fields().size() + " fields  •  " + value.methods().size()
                        + " methods  •  " + value.strings().size() + " string constants")
        );
        List<String> rows = new ArrayList<>();
        value.fields().forEach(field -> rows.add("FIELD  " + prefix(field.access())
                + field.name() + " : " + field.descriptor()));
        value.methods().forEach(method -> rows.add((method.mainMethod() ? "MAIN   " : "METHOD ")
                + prefix(method.access()) + method.name() + method.descriptor()
                + "  ·  " + method.instructions() + " instructions"));
        members.setItems(FXCollections.observableArrayList(rows));
        strings.setItems(FXCollections.observableArrayList(value.strings()));
        calls.setItems(FXCollections.observableArrayList(value.calls()));
        findings.setItems(FXCollections.observableArrayList(value.findings()));
        bytecode.replaceText(value.bytecode());
        bytecode.setStyleSpans(0, BytecodeSyntax.highlight(value.bytecode()));
        bytecode.moveTo(0);

        if (value.rawBytes() != null && value.rawBytes().length > 0) {
            buildHexTable(value.rawBytes());
        } else {
            hexTableContent.getChildren().setAll(emptyHexLabel("No raw class bytes available."));
        }

        if (value.constantPool() != null && !value.constantPool().isEmpty()) {
            buildConstantPoolView(value.constantPool());
        } else {
            constantPoolContent.getChildren().setAll(emptyHexLabel("No constant pool entries."));
        }
    }

    private static Label emptyHexLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hex-empty-label");
        return label;
    }

    private void buildHexTable(byte[] data) {
        hexTableContent.getChildren().clear();

        // Header row
        HBox header = new HBox();
        header.getStyleClass().add("hex-header-row");
        Label offsetHeader = new Label("OFFSET");
        offsetHeader.getStyleClass().addAll("hex-cell", "hex-header-cell", "hex-offset-header");
        offsetHeader.setMinWidth(90);
        offsetHeader.setMaxWidth(90);
        Label hexHeader = new Label("00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F");
        hexHeader.getStyleClass().addAll("hex-cell", "hex-header-cell", "hex-bytes-header");
        HBox.setHgrow(hexHeader, Priority.ALWAYS);
        Label asciiHeader = new Label("ASCII");
        asciiHeader.getStyleClass().addAll("hex-cell", "hex-header-cell", "hex-ascii-header");
        asciiHeader.setMinWidth(160);
        asciiHeader.setMaxWidth(160);
        header.getChildren().addAll(offsetHeader, hexHeader, asciiHeader);
        hexTableContent.getChildren().add(header);

        // Data rows
        for (int i = 0; i < data.length; i += 16) {
            HBox row = new HBox();
            row.getStyleClass().add(i / 16 % 2 == 0 ? "hex-row-even" : "hex-row-odd");

            // Offset cell
            Label offsetLabel = new Label(String.format("%08X", i));
            offsetLabel.getStyleClass().addAll("hex-cell", "hex-offset");
            offsetLabel.setMinWidth(90);
            offsetLabel.setMaxWidth(90);

            // Hex bytes cell
            StringBuilder hexBytes = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < data.length) {
                    hexBytes.append(String.format("%02X ", data[i + j]));
                } else {
                    hexBytes.append("   ");
                }
                if (j == 7) hexBytes.append(" ");
            }
            Label hexLabel = new Label(hexBytes.toString().stripTrailing());
            hexLabel.getStyleClass().addAll("hex-cell", "hex-bytes");
            HBox.setHgrow(hexLabel, Priority.ALWAYS);

            // ASCII cell – colorize printable vs non-printable
            HBox asciiBox = new HBox();
            asciiBox.getStyleClass().add("hex-ascii-box");
            asciiBox.setMinWidth(160);
            asciiBox.setMaxWidth(160);
            for (int j = 0; j < 16; j++) {
                if (i + j < data.length) {
                    byte b = data[i + j];
                    Label ch = new Label(String.valueOf((b >= 32 && b <= 126) ? (char) b : '.'));
                    ch.getStyleClass().add("hex-ascii-char");
                    if (b >= 32 && b <= 126) {
                        ch.getStyleClass().add("hex-ascii-printable");
                    } else if (b == 0) {
                        ch.getStyleClass().add("hex-ascii-null");
                    } else {
                        ch.getStyleClass().add("hex-ascii-nonprint");
                    }
                    asciiBox.getChildren().add(ch);
                }
            }

            row.getChildren().addAll(offsetLabel, hexLabel, asciiBox);
            hexTableContent.getChildren().add(row);
        }
    }

    private void buildConstantPoolView(List<String> entries) {
        constantPoolContent.getChildren().clear();

        // Header
        Label header = new Label("Constant Pool  ·  " + entries.size() + " entries");
        header.getStyleClass().add("cp-header");
        constantPoolContent.getChildren().add(header);

        for (String entry : entries) {
            HBox row = new HBox(8);
            row.getStyleClass().add("cp-entry-row");

            // Parse entry: "#1   = String            Hello"
            int eqIdx = entry.indexOf('=');
            if (eqIdx > 0) {
                String index = entry.substring(0, eqIdx).trim();
                String rest = entry.substring(eqIdx + 1).trim();
                int spaceIdx = rest.indexOf(' ');
                String type;
                String value2;
                if (spaceIdx > 0) {
                    type = rest.substring(0, spaceIdx).trim();
                    value2 = rest.substring(spaceIdx).trim();
                } else {
                    type = rest;
                    value2 = "";
                }

                Label indexLabel = new Label(index);
                indexLabel.getStyleClass().addAll("cp-cell", "cp-index");
                indexLabel.setMinWidth(60);
                indexLabel.setMaxWidth(60);

                Label typeLabel = new Label(type);
                typeLabel.getStyleClass().addAll("cp-cell", "cp-type");
                typeLabel.setMinWidth(140);
                typeLabel.setMaxWidth(140);
                applyCpTypeStyle(typeLabel, type);

                Label valueLabel = new Label(value2);
                valueLabel.getStyleClass().addAll("cp-cell", "cp-value");
                HBox.setHgrow(valueLabel, Priority.ALWAYS);

                row.getChildren().addAll(indexLabel, typeLabel, valueLabel);
            } else {
                Label plain = new Label(entry);
                plain.getStyleClass().addAll("cp-cell", "cp-value");
                HBox.setHgrow(plain, Priority.ALWAYS);
                row.getChildren().add(plain);
            }

            constantPoolContent.getChildren().add(row);
        }
    }

    private static void applyCpTypeStyle(Label label, String type) {
        switch (type) {
            case "String" -> label.getStyleClass().add("cp-type-string");
            case "Integer", "Long", "Float", "Double" -> label.getStyleClass().add("cp-type-number");
            case "Class" -> label.getStyleClass().add("cp-type-class");
            case "Fieldref", "Methodref", "InterfaceMethodref" -> label.getStyleClass().add("cp-type-ref");
            case "NameAndType" -> label.getStyleClass().add("cp-type-nat");
            case "Utf8" -> label.getStyleClass().add("cp-type-utf8");
            default -> label.getStyleClass().add("cp-type-other");
        }
    }

    private void showResource(ArchiveNode node) {
        Path archive = node != null && node.archivePath != null ? node.archivePath : activeArchive();
        if (archive == null) return;
        editorTabs.getSelectionModel().select(0);
        long token = operation.incrementAndGet();
        setLoading(true);
        documentTitle.setText(node.path);
        documentMeta.setText(formatBytes(node.size) + "  •  resource preview");
        status.setText("Reading " + node.path + "…");
        clearInspection();
        service.resource(archive, node.path).whenComplete((text, error) -> Platform.runLater(() -> {
            if (token != operation.get()) return;
            setLoading(false);
            if (error != null) {
                source.replaceText("Resource preview failed.\n\n" + message(error));
                status.setText("Resource preview failed");
            } else {
                source.replaceText(text);
                source.clearStyle(0, source.getLength());
                source.moveTo(0);
                status.setText("Resource preview");
            }
        }));
    }

    private void showManifest() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        editorTabs.getSelectionModel().select(0);
        long token = operation.incrementAndGet();
        setLoading(true);
        documentTitle.setText("META-INF/MANIFEST.MF (" + archive.getFileName() + ")");
        documentMeta.setText("Archive manifest  •  read-only");
        status.setText("Reading manifest…");
        clearInspection();
        service.manifest(archive).whenComplete((text, error) -> Platform.runLater(() -> {
            if (token != operation.get()) return;
            setLoading(false);
            source.replaceText(error == null ? text : "Manifest read failed.\n\n" + message(error));
            source.clearStyle(0, source.getLength());
            source.moveTo(0);
            status.setText(error == null ? "Manifest loaded" : "Manifest read failed");
        }));
    }

    private void showScanResult(ScanView view) {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        closeToolDrawer();
        setInspectorVisible(true);
        long token = operation.incrementAndGet();
        status.setText("Scanning every class in " + archive.getFileName() + " for " + view.label + "…");
        service.scan(archive).whenComplete((scan, error) -> Platform.runLater(() -> {
            if (token != operation.get()) return;
            if (error != null) {
                fail("Archive scan failed", error);
                return;
            }
            switch (view) {
                case MAINS -> {
                    members.setItems(FXCollections.observableArrayList(scan.mainMethods().stream()
                            .map(item -> "MAIN   " + item.className() + "." + item.name() + item.descriptor())
                            .toList()));
                    inspectorTabs.getSelectionModel().select(1);
                }
                case STRINGS -> {
                    strings.setItems(FXCollections.observableArrayList(scan.strings()));
                    inspectorTabs.getSelectionModel().select(2);
                }
                case CALLS -> {
                    calls.setItems(FXCollections.observableArrayList(scan.calls()));
                    inspectorTabs.getSelectionModel().select(3);
                }
                case FINDINGS -> {
                    findings.setItems(FXCollections.observableArrayList(scan.findings()));
                    inspectorTabs.getSelectionModel().select(4);
                }
            }
            status.setText(view.complete(scan) + (scan.errors().isEmpty() ? ""
                    : "  •  " + scan.errors().size() + " unreadable class(es)"));
        }));
    }

    private void showReplaceTool() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        TextField find = input("Exact string constant");
        TextField replacement = input("Replacement");
        Button export = Ui.button("Export modified copy", "primary-button", () -> {});
        export.setOnAction(event -> {
            if (find.getText().isEmpty()) {
                context.notifications().show("Enter an exact string constant to replace");
                return;
            }
            chooseOutput("-strings.jar").ifPresent(output -> runExport(export,
                    () -> service.replaceStrings(archive, output, find.getText(), replacement.getText())));
        });
        showTool("Replace exact string constants",
                "Only matching constant-pool strings are changed. Substrings and resources are left untouched.",
                Ui.fieldRow("Find", find), Ui.fieldRow("Replace with", replacement), export);
    }

    private void showFramesTool() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        Button export = Ui.button("Export frame-stripped copy", "danger-button", () -> {});
        export.setOnAction(event -> {
            boolean confirmed = context.dialogs().confirm("Strip stack-map frames?",
                    "This is an anti-analysis experiment. Java 7+ bytecode can fail verification when frames are absent. "
                            + "Frostfuscator will write a new archive and keep the input untouched.",
                    "Export copy");
            if (!confirmed) return;
            chooseOutput("-no-frames.jar").ifPresent(output -> runExport(export,
                    () -> service.removeFrames(archive, output)));
        });
        showTool("Strip stack-map frames",
                "Advanced compatibility-breaking export. Use only when you understand JVM verification rules.", export);
    }

    private void showVersionTool() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        List<ClassVersionChoice> choices = new ArrayList<>();
        for (int major = 45; major <= 70; major++) choices.add(new ClassVersionChoice(major));
        CustomComboBox<ClassVersionChoice> version = new CustomComboBox<>(choices,
                ClassVersionChoice::label);
        version.setValue(choices.stream().filter(choice -> choice.major == 65).findFirst().orElse(choices.getLast()));
        Button export = Ui.button("Export versioned copy", "primary-button", () -> {});
        export.setOnAction(event -> {
            ClassVersionChoice selected = version.getValue();
            if (selected == null) return;
            chooseOutput("-java" + javaVersion(selected.major) + ".jar").ifPresent(output ->
                    runExport(export, () -> service.changeVersions(archive, output, selected.major)));
        });
        showTool("Change class-file versions",
                "This changes class headers only; it does not rewrite language features or APIs. Lowering a version "
                        + "can produce invalid bytecode, so test the exported copy.", Ui.fieldRow("Target", version), export);
    }

    private void showTool(String title, String copy, Node... controls) {
        setInspectorVisible(true);
        Label heading = Ui.label(title, "viewer-tool-title");
        Label description = Ui.label(copy, "viewer-tool-copy");
        description.setWrapText(true);
        Button close = compactButton("Close", this::closeToolDrawer);
        HBox header = new HBox(Ui.SPACE_3, heading, Ui.spacer(), close);
        header.setAlignment(Pos.CENTER_LEFT);
        toolDrawer.getChildren().setAll(header, description);
        toolDrawer.getChildren().addAll(controls);
        VBox.setVgrow(toolDrawer, Priority.ALWAYS);
        inspectorTabs.setVisible(false);
        inspectorTabs.setManaged(false);
        toolDrawer.setVisible(true);
        toolDrawer.setManaged(true);
    }

    private void runExport(Button button,
                           Supplier<java.util.concurrent.CompletableFuture<ArchiveRewriteService.RewriteSummary>> job) {
        button.setDisable(true);
        status.setText("Writing transformed archive copy…");
        try {
            job.get().whenComplete((summary, error) -> Platform.runLater(() -> {
                button.setDisable(false);
                if (error != null) {
                    fail("Archive export failed", error);
                    return;
                }
                status.setText(summary.message());
                context.notifications().show("Exported " + summary.output().getFileName());
                closeToolDrawer();
            }));
        } catch (RuntimeException exception) {
            button.setDisable(false);
            fail("Archive export failed", exception);
        }
    }

    private Optional<Path> chooseOutput(String suffix) {
        Path archive = activeArchive();
        if (archive == null) return Optional.empty();
        String file = archive.getFileName().toString().replaceFirst("(?i)\\.jar$", "");
        return context.dialogs().saveJar(file + suffix);
    }

    // ── Export Tools Action Handlers ─────────────────────────────────────────────

    private void exportSelectedClassJava() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive() || selectedNode == null || selectedNode.kind != NodeKind.CLASS) {
            context.notifications().show("Select a .class entry in the tree first");
            return;
        }
        String className = selectedNode.path;
        context.dialogs().saveFile("Export selected class as .java", selectedNode.name.replace(".class", ".java"), "Java source files", "*.java")
                .ifPresent(targetFile -> {
                    status.setText("Exporting " + className + " as .java…");
                    service.exportSources(archive, List.of(decompiler.getValue()), ExportOptions.defaults(), targetFile.getParent(), className)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported " + targetFile.getFileName());
                                    status.setText("Exported " + targetFile.getFileName());
                                }
                            }));
                });
    }

    private void exportSelectedPackageSources() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive() || selectedNode == null) {
            context.notifications().show("Select a package or class in the tree first");
            return;
        }
        String pkg = selectedNode.path;
        context.dialogs().chooseDirectory("Choose export directory for package sources")
                .ifPresent(targetDir -> {
                    status.setText("Exporting package " + pkg + "…");
                    service.exportSources(archive, List.of(decompiler.getValue()), ExportOptions.defaults(), targetDir, pkg)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported package to " + targetDir.getFileName());
                                    status.setText("Exported " + summary.exportedFilesCount() + " package file(s)");
                                }
                            }));
                });
    }

    private void exportCompleteProjectSources() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export folder for complete project sources")
                .ifPresent(targetDir -> {
                    status.setText("Exporting complete project sources…");
                    service.exportSources(archive, List.of(decompiler.getValue()), ExportOptions.defaults(), targetDir, null)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported project to " + targetDir.getFileName());
                                    status.setText("Exported " + summary.exportedFilesCount() + " source files");
                                }
                            }));
                });
    }

    private void exportSourceZip() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        String suggested = archive.getFileName().toString().replaceFirst("(?i)\\.jar$", "") + "-sources.zip";
        context.dialogs().saveZip(suggested).ifPresent(targetZip -> {
            status.setText("Creating source ZIP archive…");
            service.exportSourceZip(archive, decompiler.getValue(), ExportOptions.defaults(), targetZip)
                    .whenComplete((summary, error) -> Platform.runLater(() -> {
                        if (error != null) fail("Export failed", error);
                        else {
                            context.notifications().show("Created " + targetZip.getFileName());
                            status.setText("Exported source ZIP with " + summary.exportedFilesCount() + " entries");
                        }
                    }));
        });
    }

    private void exportAllDecompilers() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export directory for multi-decompiler sources")
                .ifPresent(targetDir -> {
                    status.setText("Exporting sources across all decompilers…");
                    service.exportSources(archive, service.backends(), ExportOptions.defaults(), targetDir, null)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported multi-decompiler outputs to " + targetDir.getFileName());
                                    status.setText("Exported " + summary.exportedFilesCount() + " files across all decompilers");
                                }
                            }));
                });
    }

    private void exportComparisonReport() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export directory for comparison report")
                .ifPresent(targetDir -> {
                    status.setText("Generating decompiler comparison report…");
                    service.exportSources(archive, service.backends(), ExportOptions.defaults(), targetDir, null)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Report generation failed", error);
                                else {
                                    context.notifications().show("Generated comparison report in " + targetDir.getFileName());
                                    status.setText("Generated decompiler comparison report in reports/");
                                }
                            }));
                });
    }

    private void exportSelectedClassBytecode() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive() || selectedNode == null || selectedNode.kind != NodeKind.CLASS) {
            context.notifications().show("Select a .class entry in the tree first");
            return;
        }
        String className = selectedNode.path;
        context.dialogs().saveFile("Export selected class as .class", selectedNode.name, "Class files", "*.class")
                .ifPresent(targetFile -> {
                    status.setText("Exporting " + className + " as .class…");
                    service.exportRawBytecode(archive, targetFile.getParent(), className, false, false, false, false)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported " + targetFile.getFileName());
                                    status.setText("Exported " + targetFile.getFileName());
                                }
                            }));
                });
    }

    private void exportPackageBytecode() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive() || selectedNode == null) {
            context.notifications().show("Select a package or class in the tree first");
            return;
        }
        String pkg = selectedNode.path;
        context.dialogs().chooseDirectory("Choose export directory for package bytecode")
                .ifPresent(targetDir -> {
                    status.setText("Exporting package bytecode for " + pkg + "…");
                    service.exportRawBytecode(archive, targetDir, pkg, false, false, false, false)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported package bytecode");
                                    status.setText("Exported " + summary.exportedFilesCount() + " bytecode file(s)");
                                }
                            }));
                });
    }

    private void exportAllClassesBytecode() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export directory for all class bytecode")
                .ifPresent(targetDir -> {
                    status.setText("Exporting all class bytecode…");
                    service.exportRawBytecode(archive, targetDir, null, false, false, false, false)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported all class bytecode");
                                    status.setText("Exported " + summary.exportedFilesCount() + " bytecode files");
                                }
                            }));
                });
    }

    private void exportAsmTextifier() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export directory for ASM Textifier (.bytecode.asm)")
                .ifPresent(targetDir -> {
                    status.setText("Generating ASM Textifier disassembly…");
                    service.exportRawBytecode(archive, targetDir, null, true, false, false, false)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported ASM Textifier disassembly");
                                    status.setText("Exported " + summary.exportedFilesCount() + " ASM textifier files");
                                }
                            }));
                });
    }

    private void exportJavapDisassembly() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export directory for JVM javap -v listing")
                .ifPresent(targetDir -> {
                    status.setText("Generating javap -v disassembly…");
                    service.exportRawBytecode(archive, targetDir, null, false, true, false, false)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported javap -v disassembly");
                                    status.setText("Exported " + summary.exportedFilesCount() + " javap disassembly files");
                                }
                            }));
                });
    }

    private void exportControlFlowGraph() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export directory for control-flow graphs (Mermaid & DOT)")
                .ifPresent(targetDir -> {
                    status.setText("Generating control-flow graphs…");
                    service.exportRawBytecode(archive, targetDir, null, false, false, true, false)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported control-flow graphs");
                                    status.setText("Exported " + summary.exportedFilesCount() + " CFG files");
                                }
                            }));
                });
    }

    private void exportMethodBytecodeOnly() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export directory for method bytecode listings")
                .ifPresent(targetDir -> {
                    status.setText("Extracting method bytecode listings…");
                    service.exportRawBytecode(archive, targetDir, null, false, false, false, true)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported method bytecode");
                                    status.setText("Exported " + summary.exportedFilesCount() + " method listings");
                                }
                            }));
                });
    }

    private void exportResourcesAndMeta() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        context.dialogs().chooseDirectory("Choose export directory for original resources & META-INF")
                .ifPresent(targetDir -> {
                    status.setText("Exporting original resources & META-INF…");
                    service.exportProjectResources(archive, targetDir)
                            .whenComplete((summary, error) -> Platform.runLater(() -> {
                                if (error != null) fail("Export failed", error);
                                else {
                                    context.notifications().show("Exported resources to " + targetDir.getFileName());
                                    status.setText("Exported " + summary.exportedFilesCount() + " resource files");
                                }
                            }));
                });
    }

    private void exportProjectInventoryJson() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        String suggested = archive.getFileName().toString().replaceFirst("(?i)\\.jar$", "") + "-inventory.json";
        context.dialogs().saveFile("Export project inventory JSON", suggested, "JSON files", "*.json")
                .ifPresent(targetFile -> {
                    status.setText("Scanning project inventory…");
                    CompletableFuture.runAsync(() -> {
                        try {
                            ProjectInventory inventory = ProjectInventory.scan(archive);
                            Files.write(targetFile, inventory.toJson().getBytes(StandardCharsets.UTF_8));
                            Platform.runLater(() -> {
                                context.notifications().show("Exported inventory JSON");
                                status.setText("Exported inventory JSON to " + targetFile.getFileName());
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> fail("Export failed", ex));
                        }
                    });
                });
    }

    private void rebuildSanitizedJar() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        String suggested = archive.getFileName().toString().replaceFirst("(?i)\\.jar$", "") + "-sanitized.jar";
        context.dialogs().saveJar(suggested).ifPresent(targetJar -> {
            status.setText("Rebuilding sanitized JAR archive…");
            service.rebuildSanitizedJar(archive, targetJar)
                    .whenComplete((summary, error) -> Platform.runLater(() -> {
                        if (error != null) fail("Sanitized rebuild failed", error);
                        else {
                            context.notifications().show("Rebuilt sanitized JAR " + targetJar.getFileName());
                            status.setText("Rebuilt sanitized JAR with " + summary.exportedFilesCount() + " clean entries");
                        }
                    }));
        });
    }

    private void showStringExportWorkbench() {
        Path archive = activeArchive();
        if (archive == null || !requireArchive()) return;
        stringToggle.setSelected(true);
        status.setText("Scanning all strings in archive…");

        service.scanAllStrings(archive, true).whenComplete((allRecords, error) -> Platform.runLater(() -> {
            if (error != null) {
                fail("String scan failed", error);
                return;
            }

            status.setText("Found " + allRecords.size() + " string occurrence(s)");
            cachedRecords = allRecords;
            populateStringWorkbench(allRecords);
            updateWorkspacePanels();
        }));
    }

    private void populateStringWorkbench(List<StringRecord> allRecords) {
        stringWorkbenchContent.getChildren().clear();

        // ── Toggle Filters (FlowPane wraps automatically) ─────────────
        CheckBox uniqueOnly = new CheckBox("Unique");
        CheckBox encodedOnly = new CheckBox("Encoded");
        CheckBox highEntropyOnly = new CheckBox("H≥4");
        CheckBox appOnly = new CheckBox("App only");
        CheckBox noShort = new CheckBox("Skip ≤2");
        CheckBox decoded = new CheckBox("Decoded");
        decoded.setSelected(true);
        noShort.setSelected(true);
        for (CheckBox cb : new CheckBox[]{uniqueOnly, encodedOnly, highEntropyOnly, appOnly, noShort, decoded}) {
            cb.setStyle("-fx-font-size: 0.85em;");
        }
        FlowPane toggles = new FlowPane(6, 4, uniqueOnly, encodedOnly, highEntropyOnly, appOnly, noShort, decoded);

        // ── Dropdowns (full-width, stacked vertically) ────────────────
        ComboBox<String> categoryFilter = new ComboBox<>(FXCollections.observableArrayList(buildCategoryList(allRecords)));
        categoryFilter.setValue("All Categories");
        categoryFilter.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> sourceTypeFilter = new ComboBox<>(FXCollections.observableArrayList(buildSourceTypeList(allRecords)));
        sourceTypeFilter.setValue("All Sources");
        sourceTypeFilter.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> sortBy = new ComboBox<>(FXCollections.observableArrayList(
                "Default", "Value A→Z", "Value Z→A", "Entropy ↑", "Entropy ↓",
                "Freq ↑", "Freq ↓", "Len ↑", "Len ↓", "Category", "Class"));
        sortBy.setValue("Default");
        sortBy.setMaxWidth(Double.MAX_VALUE);

        HBox.setHgrow(categoryFilter, Priority.ALWAYS);
        HBox.setHgrow(sourceTypeFilter, Priority.ALWAYS);
        HBox.setHgrow(sortBy, Priority.ALWAYS);

        // ── Search ────────────────────────────────────────────────────
        TextField searchField = input("Search…");
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        ComboBox<String> searchMode = new ComboBox<>(FXCollections.observableArrayList(
                "Contains", "Exact", "Regex", "Starts", "Ends", "NOT"));
        searchMode.setValue("Contains");
        searchMode.setMaxWidth(Double.MAX_VALUE);

        HBox searchRow = new HBox(4, searchField, searchMode);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchMode.setMinWidth(70);
        searchMode.setPrefWidth(85);

        // ── Package / Class / Length filters ──────────────────────────
        TextField pkgField = input("Package…");
        pkgField.setMaxWidth(Double.MAX_VALUE);
        TextField clsField = input("Class…");
        clsField.setMaxWidth(Double.MAX_VALUE);
        HBox scopeRow = new HBox(4, pkgField, clsField);
        HBox.setHgrow(pkgField, Priority.ALWAYS);
        HBox.setHgrow(clsField, Priority.ALWAYS);

        TextField minLen = input("Min");
        minLen.setPrefWidth(50);
        TextField maxLen = input("Max");
        maxLen.setPrefWidth(50);
        Label lenLabel = Ui.label("Length:", "viewer-label");
        lenLabel.setStyle("-fx-font-size: 0.85em;");
        HBox lenRow = new HBox(4, lenLabel, minLen, maxLen);
        lenRow.setAlignment(Pos.CENTER_LEFT);

        // ── Stats ─────────────────────────────────────────────────────
        Label statsLabel = Ui.label("", "viewer-string-meta");
        statsLabel.setWrapText(true);
        statsLabel.setStyle("-fx-font-size: 0.82em;");

        // ── String List ───────────────────────────────────────────────
        ListView<StringRecord> stringList = new ListView<>();
        stringList.getStyleClass().add("viewer-list");
        VBox.setVgrow(stringList, Priority.ALWAYS);

        // ── Filter Logic ──────────────────────────────────────────────
        Runnable applyFilters = () -> {
            int minLenVal = 0, maxLenVal = Integer.MAX_VALUE;
            try { if (!minLen.getText().isBlank()) minLenVal = Integer.parseInt(minLen.getText().trim()); } catch (NumberFormatException ignored) {}
            try { if (!maxLen.getText().isBlank()) maxLenVal = Integer.parseInt(maxLen.getText().trim()); } catch (NumberFormatException ignored) {}

            String pkg = pkgField.getText().toLowerCase(Locale.ROOT).trim();
            String cls = clsField.getText().toLowerCase(Locale.ROOT).trim();
            String search = searchField.getText().trim();
            String mode = searchMode.getValue();
            String selCat = categoryFilter.getValue();
            String selSrc = sourceTypeFilter.getValue();

            java.util.function.Predicate<String> pred = buildSearchPredicate(search, mode);

            Set<String> seen = new HashSet<>();
            List<StringRecord> filtered = new ArrayList<>();
            int encCount = 0;
            double eSum = 0, eMin = Double.MAX_VALUE, eMax = 0;
            Map<String, Integer> cats = new LinkedHashMap<>();

            for (StringRecord r : allRecords) {
                if (uniqueOnly.isSelected() && !seen.add(r.value())) continue;
                if (noShort.isSelected() && r.value().length() <= 2) continue;
                if (r.value().length() < minLenVal || r.value().length() > maxLenVal) continue;
                if (!pkg.isEmpty() && !r.className().toLowerCase(Locale.ROOT).contains(pkg)) continue;
                if (!cls.isEmpty() && !r.className().toLowerCase(Locale.ROOT).contains(cls)) continue;
                if (encodedOnly.isSelected() && !r.likelyEncoded()) continue;
                if (highEntropyOnly.isSelected() && r.entropy() < 4.0) continue;
                if (appOnly.isSelected() && isLibraryClass(r.className())) continue;
                if (!"All Categories".equals(selCat) && !selCat.equals(r.category())) continue;
                if (!"All Sources".equals(selSrc) && !selSrc.equals(r.sourceType())) continue;
                if (pred != null && !pred.test(r.value()) && !pred.test(r.decodedValue())) continue;

                filtered.add(r);
                if (r.likelyEncoded()) encCount++;
                eSum += r.entropy();
                if (r.entropy() < eMin) eMin = r.entropy();
                if (r.entropy() > eMax) eMax = r.entropy();
                cats.merge(r.category(), 1, Integer::sum);
            }

            sortStringRecords(filtered, sortBy.getValue());
            stringList.setItems(FXCollections.observableArrayList(filtered));

            double avg = filtered.isEmpty() ? 0 : eSum / filtered.size();
            StringBuilder sb = new StringBuilder();
            sb.append(filtered.size()).append("/").append(allRecords.size());
            sb.append("  Enc:").append(encCount);
            if (!filtered.isEmpty()) {
                sb.append("  H:").append(String.format("%.1f", eMin == Double.MAX_VALUE ? 0 : eMin));
                sb.append("-").append(String.format("%.1f", avg));
                sb.append("-").append(String.format("%.1f", eMax));
            }
            statsLabel.setText(sb.toString());
            status.setText("Showing " + filtered.size() + " of " + allRecords.size() + " strings");
        };

        for (CheckBox cb : new CheckBox[]{uniqueOnly, encodedOnly, highEntropyOnly, appOnly, noShort}) {
            cb.setOnAction(e -> applyFilters.run());
        }
        decoded.setOnAction(e -> { applyFilters.run(); stringList.refresh(); });
        categoryFilter.setOnAction(e -> applyFilters.run());
        sourceTypeFilter.setOnAction(e -> applyFilters.run());
        sortBy.setOnAction(e -> applyFilters.run());
        searchMode.setOnAction(e -> applyFilters.run());
        for (TextField tf : new TextField[]{searchField, pkgField, clsField, minLen, maxLen}) {
            tf.textProperty().addListener((o, ov, nv) -> applyFilters.run());
        }

        stringList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(StringRecord item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                VBox box = new VBox(1);
                Label val = new Label(truncate(item.value(), 300));
                val.setStyle("-fx-text-fill: -fx-text; -fx-font-size: 0.92em;");
                val.setWrapText(true);

                Label meta = new Label("[" + item.category() + "] " + shortClass(item.className())
                        + "." + item.methodName() + "  H=" + String.format("%.1f", item.entropy())
                        + " f=" + item.frequency() + " " + item.sourceType());
                meta.setStyle("-fx-text-fill: -fx-text-muted; -fx-font-size: 0.8em;");
                meta.setWrapText(true);
                box.getChildren().addAll(val, meta);

                if (decoded.isSelected() && item.likelyEncoded() && !item.decodedValue().equals(item.value())) {
                    Label dec = new Label("↳ " + truncate(item.decodedValue(), 120));
                    dec.setStyle("-fx-text-fill: #6A8759; -fx-font-size: 0.8em;");
                    dec.setWrapText(true);
                    box.getChildren().add(dec);
                }
                setGraphic(box);
            }
        });

        // ── Action Buttons (FlowPane wraps) ──────────────────────────
        Button copyVal = compactButton("Copy", () -> {
            StringRecord sel = stringList.getSelectionModel().getSelectedItem();
            if (sel != null) { ClipboardContent c = new ClipboardContent(); c.putString(sel.value()); Clipboard.getSystemClipboard().setContent(c); context.notifications().show("Copied"); }
        });
        Button copyDec = compactButton("Copy decoded", () -> {
            StringRecord sel = stringList.getSelectionModel().getSelectedItem();
            if (sel != null) { ClipboardContent c = new ClipboardContent(); c.putString(sel.decodedValue()); Clipboard.getSystemClipboard().setContent(c); }
        });
        Button copyAll = compactButton("Copy all", () -> {
            StringBuilder sb = new StringBuilder();
            stringList.getItems().forEach(r -> sb.append(r.value()).append("\n"));
            ClipboardContent c = new ClipboardContent(); c.putString(sb.toString()); Clipboard.getSystemClipboard().setContent(c);
            context.notifications().show("Copied " + stringList.getItems().size() + " strings");
        });
        Button jumpBc = compactButton("→ Bytecode", () -> {
            StringRecord sel = stringList.getSelectionModel().getSelectedItem();
            if (sel != null && sel.className() != null && !sel.className().isEmpty())
                jumpToClassAndInstruction(sel.className(), sel.instructionIndex());
        });
        Button jumpSrc = compactButton("→ Source", () -> {
            StringRecord sel = stringList.getSelectionModel().getSelectedItem();
            if (sel != null && sel.className() != null && !sel.className().isEmpty()) {
                String path = sel.className().replace('.', '/') + ".class";
                TreeItem<ArchiveNode> found = findTreeItem(hierarchy.getRoot(), path);
                if (found != null) { hierarchy.getSelectionModel().select(found); select(found.getValue()); editorTabs.getSelectionModel().select(0); }
            }
        });

        ComboBox<String> exportFmt = new ComboBox<>(FXCollections.observableArrayList("JSON", "CSV", "TXT", "JSONL"));
        exportFmt.setValue("JSON");
        exportFmt.setMaxWidth(80);
        exportFmt.setStyle("-fx-font-size: 0.85em;");

        Button exportBtn = compactButton("Export", () -> {
            String fmt = exportFmt.getValue().toLowerCase();
            Path archive = activeArchive();
            String prefix = archive != null ? archive.getFileName().toString().replaceFirst("(?i)\\.jar$", "") : "archive";
            String suggested = prefix + "-strings." + fmt;
            context.dialogs().saveFile("Export strings", suggested, fmt.toUpperCase() + " files", "*." + fmt)
                    .ifPresent(targetFile -> service.exportStrings(stringList.getItems(), targetFile, fmt)
                            .whenComplete((v, err) -> Platform.runLater(() -> {
                                if (err != null) fail("Export failed", err);
                                else context.notifications().show("Exported " + stringList.getItems().size() + " strings");
                            })));
        });

        FlowPane actions = new FlowPane(4, 4, copyVal, copyDec, copyAll, jumpBc, jumpSrc, exportFmt, exportBtn);

        VBox filtersBox = new VBox(4, toggles, categoryFilter, sourceTypeFilter, sortBy, searchRow, scopeRow, lenRow);
        filtersBox.setPadding(new Insets(0, 0, 4, 0));

        stringWorkbenchContent.getChildren().setAll(filtersBox, statsLabel, stringList, actions);
        applyFilters.run();
    }

    private static String shortClass(String className) {
        if (className == null) return "";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "…";
    }

    private static boolean isLibraryClass(String className) {
        return className.startsWith("java.") || className.startsWith("javax.")
                || className.startsWith("sun.") || className.startsWith("com.sun.")
                || className.startsWith("org.apache.") || className.startsWith("org.slf4j.")
                || className.startsWith("org.objectweb.") || className.startsWith("kotlin.")
                || className.startsWith("scala.") || className.startsWith("groovy.")
                || className.startsWith("org.gradle.") || className.startsWith("com.google.common.");
    }

    private static List<String> buildCategoryList(List<StringRecord> records) {
        List<String> cats = new ArrayList<>();
        cats.add("All Categories");
        records.stream().map(StringRecord::category).distinct().sorted().forEach(cats::add);
        return cats;
    }

    private static List<String> buildSourceTypeList(List<StringRecord> records) {
        List<String> sources = new ArrayList<>();
        sources.add("All Sources");
        records.stream().map(StringRecord::sourceType).distinct().sorted().forEach(sources::add);
        return sources;
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.Predicate<String> buildSearchPredicate(String search, String mode) {
        if (search == null || search.isEmpty()) return null;
        return switch (mode) {
            case "Contains", "Contains (case-insensitive)" -> {
                String lower = search.toLowerCase(Locale.ROOT);
                yield s -> s != null && s.toLowerCase(Locale.ROOT).contains(lower);
            }
            case "Exact", "Exact match" -> s -> search.equals(s);
            case "Regex" -> {
                try {
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(search, java.util.regex.Pattern.CASE_INSENSITIVE);
                    yield s -> s != null && p.matcher(s).find();
                } catch (Exception e) {
                    yield null;
                }
            }
            case "Starts", "Starts with" -> {
                String lower = search.toLowerCase(Locale.ROOT);
                yield s -> s != null && s.toLowerCase(Locale.ROOT).startsWith(lower);
            }
            case "Ends", "Ends with" -> {
                String lower = search.toLowerCase(Locale.ROOT);
                yield s -> s != null && s.toLowerCase(Locale.ROOT).endsWith(lower);
            }
            case "NOT", "NOT contains" -> {
                String lower = search.toLowerCase(Locale.ROOT);
                yield s -> s == null || !s.toLowerCase(Locale.ROOT).contains(lower);
            }
            default -> null;
        };
    }

    private static void sortStringRecords(List<StringRecord> list, String sortMode) {
        switch (sortMode) {
            case "Value A→Z" -> list.sort(Comparator.comparing(StringRecord::value, String.CASE_INSENSITIVE_ORDER));
            case "Value Z→A" -> list.sort(Comparator.comparing(StringRecord::value, String.CASE_INSENSITIVE_ORDER).reversed());
            case "Entropy ↑" -> list.sort(Comparator.comparingDouble(StringRecord::entropy));
            case "Entropy ↓" -> list.sort(Comparator.comparingDouble(StringRecord::entropy).reversed());
            case "Freq ↑", "Frequency ↑" -> list.sort(Comparator.comparingInt(StringRecord::frequency));
            case "Freq ↓", "Frequency ↓" -> list.sort(Comparator.comparingInt(StringRecord::frequency).reversed());
            case "Len ↑", "Length ↑" -> list.sort(Comparator.comparingInt(r -> r.value().length()));
            case "Len ↓", "Length ↓" -> list.sort(Comparator.<StringRecord, Integer>comparing(r -> r.value().length()).reversed());
            case "Category" -> list.sort(Comparator.comparing(StringRecord::category));
            case "Class", "Class name" -> list.sort(Comparator.comparing(StringRecord::className, String.CASE_INSENSITIVE_ORDER));
            default -> {} // keep original order
        }
    }

    private void jumpToClassAndInstruction(String className, int insnIdx) {
        // Preserve the tool drawer state so the string workbench stays open
        boolean toolDrawerWasVisible = toolDrawer.isVisible();
        List<Node> drawerSnapshot = toolDrawerWasVisible
                ? new ArrayList<>(toolDrawer.getChildren()) : null;

        String path = className.replace('.', '/') + ".class";
        TreeItem<ArchiveNode> found = findTreeItem(hierarchy.getRoot(), path);
        if (found != null) {
            hierarchy.getSelectionModel().select(found);
            select(found.getValue());
            if (insnIdx >= 0) {
                editorTabs.getSelectionModel().select(1);
                // Wait for bytecode to load, then scroll to instruction
                Platform.runLater(() -> {
                    if (bytecode.getLength() > 0) {
                        bytecode.moveTo(Math.min(bytecode.getLength(), insnIdx * 20));
                        bytecode.requestFollowCaret();
                    }
                });
            }
        }

        // Restore the string workbench / tool drawer if it was open
        if (toolDrawerWasVisible && drawerSnapshot != null) {
            Platform.runLater(() -> {
                toolDrawer.getChildren().setAll(drawerSnapshot);
                inspectorTabs.setVisible(false);
                inspectorTabs.setManaged(false);
                toolDrawer.setVisible(true);
                toolDrawer.setManaged(true);
            });
        }
    }

    private TreeItem<ArchiveNode> findTreeItem(TreeItem<ArchiveNode> root, String path) {
        if (root == null) return null;
        if (root.getValue() != null && path.equals(root.getValue().path)) return root;
        for (TreeItem<ArchiveNode> child : root.getChildren()) {
            TreeItem<ArchiveNode> res = findTreeItem(child, path);
            if (res != null) return res;
        }
        return null;
    }

    private void closeToolDrawer() {
        toolDrawer.setVisible(false);
        toolDrawer.setManaged(false);
        toolDrawer.getChildren().clear();
        inspectorTabs.setVisible(true);
        inspectorTabs.setManaged(true);
        updateWorkspacePanels();
    }

    private void findNext() {
        CodeArea area = activeEditor();
        String query = sourceSearch.getText();
        if (query == null || query.isEmpty() || area.getLength() == 0) return;
        String text = area.getText();
        String needle = query;
        if (!matchCase.isSelected()) {
            text = text.toLowerCase(Locale.ROOT);
            needle = needle.toLowerCase(Locale.ROOT);
        }
        int from = Math.min(area.getLength(), Math.max(area.getSelection().getEnd(), 0));
        int index = text.indexOf(needle, from);
        if (index < 0 && from > 0) index = text.indexOf(needle);
        if (index < 0) {
            status.setText("No match for “" + query + "”");
            return;
        }
        area.selectRange(index, index + query.length());
        area.requestFollowCaret();
        status.setText("Match at line " + (1 + area.getText(0, index).chars()
                .filter(character -> character == '\n').count()));
    }

    private void copySource() {
        CodeArea area = activeEditor();
        String value = area.getSelectedText();
        if (value == null || value.isEmpty()) value = area.getText();
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
        context.notifications().show(area.getSelectedText().isEmpty() ? "Copied document" : "Copied selection");
    }

    private CodeArea activeEditor() {
        return editorTabs.getSelectionModel().getSelectedIndex() == 1 ? bytecode : source;
    }

    private void clearInspection() {
        selectedInspection = null;
        overview.getChildren().clear();
        members.getItems().clear();
        strings.getItems().clear();
        calls.getItems().clear();
        findings.getItems().clear();
        bytecode.clear();
        hexTableContent.getChildren().clear();
        constantPoolContent.getChildren().clear();
    }

    private void setLoading(boolean value) {
        sourceSkeleton.setVisible(value);
        sourceSkeleton.setManaged(value);
        refresh.setDisable(value);
    }

    private void buildSkeleton() {
        sourceSkeleton.getStyleClass().add("viewer-source-skeleton");
        for (int index = 0; index < 14; index++) {
            Region line = new Region();
            line.getStyleClass().add("viewer-skeleton-line");
            line.setMaxWidth(switch (index % 5) {
                case 0 -> 420;
                case 1 -> 610;
                case 2 -> 510;
                case 3 -> 680;
                default -> 350;
            });
            sourceSkeleton.getChildren().add(line);
        }
        sourceSkeleton.setVisible(false);
        sourceSkeleton.setManaged(false);
    }

    private boolean requireArchive() {
        if (activeArchive() != null) return true;
        context.notifications().show("Analyze an input JAR or open a JAR file before using the viewer");
        navigate.accept(PageId.INPUT);
        return false;
    }

    private void fail(String title, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        status.setText(title + ": " + message(cause));
        context.dialogs().error(title, cause);
    }

    private void requestRefresh() {
        if (active) refreshArchive();
        else refreshPending = true;
    }

    private void refreshArchive() {
        refreshPending = false;
        Path archive = context.projectState().analysis().analyzed()
                ? context.projectState().analysis().jar() : configuredInput();
        if (archive == null || !java.nio.file.Files.isRegularFile(archive)) {
            if (loadedSnapshots.isEmpty()) showArchive(null);
        } else if (!archive.toAbsolutePath().normalize().equals(primaryArchive)
                || snapshot == null || archiveChanged(archive)) {
            showArchive(archive);
        }
    }

    private boolean archiveChanged(Path archive) {
        try {
            return java.nio.file.Files.size(archive) != snapshot.size()
                    || java.nio.file.Files.getLastModifiedTime(archive).toMillis() != snapshot.modifiedMillis();
        } catch (Exception ignored) {
            return true;
        }
    }

    private Path configuredInput() {
        String input = context.projectState().configuration().getInput();
        if (input == null || input.isBlank()) return null;
        try {
            return Path.of(input);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    public Node root() {
        return root;
    }

    @Override
    public void onShown() {
        active = true;
        refreshArchive();
        openPendingGraphTarget();
    }

    @Override
    public void onHidden() {
        active = false;
        operation.incrementAndGet();
        service.cancelActiveDecompilation();
        if (pendingInspect != null) {
            pendingInspect.cancel(true);
            pendingInspect = null;
        }
    }

    private void openPendingGraphTarget() {
        String internalName = context.projectState().graphNavigationClass();
        if (internalName == null || internalName.isBlank() || hierarchy.getRoot() == null) return;
        String path = internalName.replace('.', '/');
        if (!path.endsWith(".class")) path += ".class";
        TreeItem<ArchiveNode> found = findTreeItem(hierarchy.getRoot(), path);
        if (found == null) return;
        hierarchy.getSelectionModel().select(found);
        select(found.getValue());
        context.projectState().clearGraphNavigationClass();
    }

    private static Button compactButton(String text, String iconName, Runnable action) {
        Button btn = new Button(text);
        if (iconName != null && !iconName.isEmpty()) {
            FontIcon icon = new FontIcon(iconName);
            icon.setStyle("-fx-icon-size: 13px;");
            btn.setGraphic(icon);
        }
        btn.getStyleClass().add("viewer-action");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private static Button compactButton(String text, Runnable action) {
        return compactButton(text, null, action);
    }

    private static void makeIconOnly(ButtonBase button, String accessibleText) {
        button.setText(null);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setAccessibleText(accessibleText);
        button.setTooltip(new Tooltip(accessibleText));
        button.getStyleClass().add("viewer-icon-action");
    }

    private static Node emptyStep(String number, String text) {
        Label index = Ui.label(number, "viewer-empty-step-index");
        Label label = Ui.label(text, "viewer-empty-step-label");
        HBox step = new HBox(6, index, label);
        step.setAlignment(Pos.CENTER_LEFT);
        step.getStyleClass().add("viewer-empty-step");
        return step;
    }

    private static Node emptyArrow() {
        FontIcon arrow = new FontIcon("fth-chevron-right");
        arrow.getStyleClass().add("viewer-empty-arrow");
        return arrow;
    }

    private static TextField input(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("text-input");
        return field;
    }

    private static CodeArea codeArea(String style) {
        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("viewer-code", style);
        area.setEditable(false);
        area.setWrapText(false);
        return area;
    }

    private static <T> ListView<T> list(String placeholder) {
        ListView<T> list = new ListView<>();
        list.getStyleClass().add("viewer-list");
        list.setPlaceholder(Ui.label(placeholder, "empty-state-copy"));
        return list;
    }

    private static Tab tab(String title, Node content) {
        return new Tab(title, content);
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("viewer-inspector-scroll");
        return scroll;
    }

    private static Node fact(String key, String value) {
        Label label = Ui.label(key, "viewer-fact-key");
        Label content = Ui.label(value, "viewer-fact-value");
        content.setWrapText(true);
        VBox row = new VBox(Ui.SPACE_1, label, content);
        row.getStyleClass().add("viewer-fact");
        return row;
    }

    private static Node stringRow(ArchiveInspector.StringOccurrence item) {
        Label value = Ui.label(quoted(item.value()), "viewer-list-primary", "syntax-string");
        Label location = Ui.label(item.source() + "  •  " + item.location(), "viewer-list-secondary");
        value.setWrapText(true);
        location.setWrapText(true);
        VBox row = new VBox(Ui.SPACE_1, value, location);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static Node callRow(ArchiveInspector.CallEdge item) {
        Label value = Ui.label(item.sequence() + "  " + item.target(), "viewer-list-primary");
        Label location = Ui.label(item.owner() + "." + item.method() + "  •  " + item.invocation(),
                "viewer-list-secondary");
        value.setWrapText(true);
        location.setWrapText(true);
        return new VBox(Ui.SPACE_1, value, location);
    }

    private static Node findingRow(ArchiveInspector.CapabilityFinding item) {
        Label severity = Ui.label(item.severity(), "viewer-finding-severity",
                "viewer-severity-" + item.severity().toLowerCase(Locale.ROOT));
        Label title = Ui.label(item.title(), "viewer-list-primary");
        HBox heading = new HBox(Ui.SPACE_2, severity, title);
        Label evidence = Ui.label(item.evidence(), "viewer-list-secondary");
        Label location = Ui.label(item.className() + (item.method().isBlank() ? "" : "." + item.method()),
                "viewer-list-location");
        evidence.setWrapText(true);
        location.setWrapText(true);
        return new VBox(Ui.SPACE_1, heading, evidence, location);
    }

    private static void sortTree(TreeItem<ArchiveNode> item) {
        item.getChildren().sort(Comparator
                .comparing((TreeItem<ArchiveNode> child) -> child.getValue().kind != NodeKind.FOLDER)
                .thenComparing(child -> child.getValue().name.toLowerCase(Locale.ROOT)));
        item.getChildren().forEach(BytecodeViewerPage::sortTree);
    }

    private static void expandAll(TreeItem<ArchiveNode> item) {
        item.setExpanded(true);
        item.getChildren().forEach(BytecodeViewerPage::expandAll);
    }

    private void deleteSelectedMember(String selected) {
        if (selected == null || selectedInspection == null || activeArchive() == null) return;
        String entry = selectedInspection.entryName();

        if (selected.startsWith("METHOD ") || selected.startsWith("MAIN ")) {
            String clean = selected.substring(7).trim();
            int spaceIdx = clean.indexOf(" ");
            if (spaceIdx > 0) clean = clean.substring(spaceIdx + 1).trim();
            int parenIdx = clean.indexOf("(");
            if (parenIdx > 0) {
                String mName = clean.substring(0, parenIdx);
                String mDesc = clean.substring(parenIdx);
                int dotIdx = mDesc.indexOf("  ·  ");
                if (dotIdx > 0) mDesc = mDesc.substring(0, dotIdx).trim();
                service.deleteMethod(activeArchive(), entry, mName, mDesc)
                        .thenRun(() -> Platform.runLater(() -> loadSelectedClass(true)));
            }
        } else if (selected.startsWith("FIELD ")) {
            String clean = selected.substring(6).trim();
            int spaceIdx = clean.indexOf(" ");
            if (spaceIdx > 0) clean = clean.substring(spaceIdx + 1).trim();
            int colonIdx = clean.indexOf(" : ");
            if (colonIdx > 0) {
                String fName = clean.substring(0, colonIdx).trim();
                String fDesc = clean.substring(colonIdx + 3).trim();
                service.deleteField(activeArchive(), entry, fName, fDesc)
                        .thenRun(() -> Platform.runLater(() -> loadSelectedClass(true)));
            }
        }
    }

    private void assembleAndApplyActiveEditor() {
        if (selectedNode == null || activeArchive() == null) return;
        String classEntry = selectedNode.path != null && !selectedNode.path.isBlank() ? selectedNode.path : selectedNode.name;
        if (!classEntry.endsWith(".class")) return;

        int activeTabIndex = editorTabs.getSelectionModel().getSelectedIndex();
        if (activeTabIndex == 0) {
            String sourceText = source.getText();
            setLoading(true);
            status.setText("Compiling Java source in memory…");
            service.compileSource(activeArchive(), classEntry, sourceText).whenComplete((result, error) -> Platform.runLater(() -> {
                setLoading(false);
                if (error != null) {
                    status.setText("Source compilation failed: " + message(error));
                    showErrorDrawer(List.of(new InJarJavaCompiler.CompilationDiagnostic(1, 1, "ERR", message(error), javax.tools.Diagnostic.Kind.ERROR)));
                } else if (result != null && !result.success()) {
                    status.setText("Source compilation contained errors");
                    showErrorDrawer(result.diagnostics());
                } else {
                    status.setText("Source compiled & applied to staged workspace");
                    hideErrorDrawer();
                    loadSelectedClass(true);
                    hierarchy.refresh();
                }
            }));
        } else if (activeTabIndex == 1) {
            String bytecodeText = bytecode.getText();
            setLoading(true);
            status.setText("Assembling bytecode in memory…");
            service.assembleBytecode(activeArchive(), classEntry, bytecodeText).whenComplete((result, error) -> Platform.runLater(() -> {
                setLoading(false);
                if (error != null || (result != null && !result.success())) {
                    String msg = error != null ? message(error) : (result != null ? result.errorMessage() : "Unknown assembly error");
                    status.setText("Bytecode assembly failed: " + msg);
                    showErrorDrawer(List.of(new InJarJavaCompiler.CompilationDiagnostic(1, 1, "ASM_ERR", msg, javax.tools.Diagnostic.Kind.ERROR)));
                } else {
                    status.setText("Bytecode assembled & applied to staged workspace");
                    hideErrorDrawer();
                    loadSelectedClass(true);
                    hierarchy.refresh();
                }
            }));
        }
    }

    private void openSaveJarDialog() {
        Path archive = activeArchive();
        if (archive == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Modified JAR Archive As");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR Files (*.jar)", "*.jar"));
        chooser.setInitialFileName("modified-" + archive.getFileName().toString());
        File selected = chooser.showSaveDialog(root.getScene().getWindow());
        if (selected != null) {
            Path output = selected.toPath();
            setLoading(true);
            status.setText("Saving staged JAR archive to " + output.getFileName() + "…");
            service.saveStagedJar(archive, output).whenComplete((ignored, error) -> Platform.runLater(() -> {
                setLoading(false);
                if (error != null) {
                    status.setText("Failed to save JAR: " + message(error));
                } else {
                    status.setText("Successfully saved modified JAR archive to " + output);
                }
            }));
        }
    }

    private void configureErrorDrawer() {
        errorDrawer.getStyleClass().add("viewer-error-drawer");
        errorDrawer.setMinHeight(160);
        errorDrawer.setPrefHeight(180);
        errorDrawer.setVisible(false);
        errorDrawer.setManaged(false);

        Label title = Ui.label("Compilation Diagnostics", "viewer-fact-key");
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-accent-token;");
        errorList.getStyleClass().add("viewer-list");
        VBox.setVgrow(errorList, Priority.ALWAYS);
        errorList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(InJarJavaCompiler.CompilationDiagnostic item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label lineLbl = Ui.label("Line " + item.lineNumber() + ":" + item.columnNumber(), "viewer-list-location");
                    Label msgLbl = Ui.label("[" + item.kind() + "] " + item.message(), "viewer-list-primary");
                    msgLbl.setWrapText(true);
                    if (item.kind() == javax.tools.Diagnostic.Kind.ERROR) {
                        msgLbl.setStyle("-fx-text-fill: -fx-error;");
                    } else {
                        msgLbl.setStyle("-fx-text-fill: -fx-warning;");
                    }
                    VBox cellBox = new VBox(2, lineLbl, msgLbl);
                    cellBox.setPadding(new Insets(2, 4, 2, 4));
                    setGraphic(cellBox);
                }
            }
        });

        errorList.getSelectionModel().selectedItemProperty().addListener((obs, old, diag) -> {
            if (diag != null && diag.lineNumber() > 0) {
                try {
                    int lineIndex = (int) Math.max(0, diag.lineNumber() - 1);
                    source.moveTo(lineIndex, 0);
                    source.requestFollowCaret();
                } catch (Exception ignored) {}
            }
        });

        Button closeBtn = compactButton("Dismiss", this::hideErrorDrawer);
        HBox header = new HBox(Ui.SPACE_2, title, Ui.spacer(), closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        errorDrawer.getChildren().setAll(header, errorList);
    }

    private void showErrorDrawer(List<InJarJavaCompiler.CompilationDiagnostic> diagnostics) {
        errorList.setItems(FXCollections.observableArrayList(diagnostics));
        errorDrawer.setVisible(true);
        errorDrawer.setManaged(true);
    }

    private void hideErrorDrawer() {
        errorList.getItems().clear();
        errorDrawer.setVisible(false);
        errorDrawer.setManaged(false);
    }

    private void triggerAutocompletionOnType() {
        if (activeArchive() == null) return;
        int caretPos = source.getCaretPosition();
        if (caretPos <= 0) {
            completionPopup.hide();
            return;
        }

        String text = source.getText(0, caretPos);
        int lastLineBreak = text.lastIndexOf('\n');
        String currentLine = lastLineBreak >= 0 ? text.substring(lastLineBreak + 1) : text;

        if (currentLine.trim().startsWith("import ")) {
            String importPrefix = currentLine.trim();
            int startPos = caretPos - (currentLine.length() - currentLine.indexOf("import ") - 7);
            List<IdeCompletionService.CompletionCandidate> candidates = completionService.getImportSuggestions(importPrefix);
            completionPopup.showSuggestions(source, Math.max(0, startPos), candidates, null);
        } else if (currentLine.contains(".")) {
            int dotIdx = currentLine.lastIndexOf('.');
            String target = currentLine.substring(0, dotIdx).trim();
            int lastSpace = Math.max(target.lastIndexOf(' '), Math.max(target.lastIndexOf('('), target.lastIndexOf('\t')));
            if (lastSpace >= 0) target = target.substring(lastSpace + 1).trim();

            String memberPrefix = currentLine.substring(dotIdx + 1).trim();
            int startPos = caretPos - memberPrefix.length();

            List<IdeCompletionService.CompletionCandidate> candidates = completionService.getMemberSuggestions(target, memberPrefix);
            if (!candidates.isEmpty()) {
                completionPopup.showSuggestions(source, Math.max(0, startPos), candidates, null);
            } else {
                completionPopup.hide();
            }
        } else {
            completionPopup.hide();
        }
    }

    private void indexWorkspaceSymbols() {
        if (activeArchive() != null) {
            try {
                completionService.indexJar(service.workspace(activeArchive()).getAllClassBytes());
            } catch (Exception ignored) {}
        }
    }

    private void openNewClassDialog() {
        Path archive = activeArchive();
        if (archive == null) return;
        TextInputDialog dialog = new TextInputDialog("com.example.NewClass");
        dialog.setTitle("New Class");
        dialog.setHeaderText("Create a new Java class in the JAR workspace");
        dialog.setContentText("Fully qualified class name:");
        dialog.showAndWait().ifPresent(className -> {
            if (className.isBlank()) return;
            String pkg = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            String simple = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;

            StringBuilder src = new StringBuilder();
            if (!pkg.isEmpty()) {
                src.append("package ").append(pkg).append(";\n\n");
            }
            src.append("public class ").append(simple).append(" {\n");
            src.append("    public ").append(simple).append("() {\n");
            src.append("    }\n");
            src.append("}\n");

            setLoading(true);
            status.setText("Creating new class " + className + "…");
            service.compileSource(archive, className, src.toString()).whenComplete((result, error) -> Platform.runLater(() -> {
                setLoading(false);
                if (error != null) {
                    status.setText("Failed to create class: " + message(error));
                } else {
                    status.setText("Created class " + className);
                    loadSelectedClass(true);
                    hierarchy.refresh();
                }
            }));
        });
    }

    private static String prefix(String value) {
        return value == null || value.isBlank() ? "" : value + " ";
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String quoted(String value) {
        String escaped = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String javaVersion(int major) {
        return major <= 0 ? "Unknown" : major == 45 ? "1.1" : Integer.toString(major - 44);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024d;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024d);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable value = throwable;
        while ((value instanceof CompletionException || value instanceof java.util.concurrent.ExecutionException)
                && value.getCause() != null) value = value.getCause();
        return value;
    }

    private static String message(Throwable throwable) {
        Throwable value = unwrap(throwable);
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }

    private record ArchiveNode(String name, String path, NodeKind kind, long size, int major, Path archivePath) {
        @Override
        public String toString() {
            return name;
        }
    }

    private enum NodeKind { WORKSPACE, ARCHIVE, FOLDER, CLASS, RESOURCE }

    private final class ArchiveTreeCell extends TreeCell<ArchiveNode> {
        @Override
        protected void updateItem(ArchiveNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            boolean isModified = activeArchive() != null && item.path != null && service.workspace(activeArchive()).isModified(item.path);
            String labelText = item.kind == NodeKind.CLASS ? item.name.replaceFirst("\\.class$", "") : item.name;
            if (isModified) {
                labelText += " [Modified]";
            }
            setText(labelText);

            FontIcon icon = new FontIcon(switch (item.kind) {
                case WORKSPACE -> "fth-layers";
                case ARCHIVE -> "fth-archive";
                case FOLDER -> "fth-folder";
                case CLASS -> "fth-code";
                case RESOURCE -> "fth-file-text";
            });
            icon.getStyleClass().add("viewer-tree-icon");
            setGraphic(icon);

            ContextMenu cm = new ContextMenu();
            dev.frost.obfuscator.gui.component.MenuAnimation.setup(cm);
            if (item.kind == NodeKind.ARCHIVE) {
                MenuItem remove = new MenuItem("Remove JAR (" + item.name + ")");
                remove.setGraphic(new FontIcon("fth-trash-2"));
                remove.setOnAction(e -> removeArchive(item.archivePath));

                MenuItem newClass = new MenuItem("New Class…");
                newClass.setGraphic(new FontIcon("fth-file-plus"));
                newClass.setOnAction(e -> openNewClassDialog());

                MenuItem addExtra = new MenuItem("Add Extra JAR…");
                addExtra.setGraphic(new FontIcon("fth-plus-circle"));
                addExtra.setOnAction(e -> openAddExtraJarDialog());

                MenuItem copyPath = new MenuItem("Copy Absolute Path");
                copyPath.setGraphic(new FontIcon("fth-copy"));
                copyPath.setOnAction(e -> {
                    if (item.archivePath != null) {
                        ClipboardContent content = new ClipboardContent();
                        content.putString(item.archivePath.toAbsolutePath().toString());
                        Clipboard.getSystemClipboard().setContent(content);
                    }
                });
                cm.getItems().addAll(newClass, remove, addExtra, new SeparatorMenuItem(), copyPath);
            } else if (item.kind == NodeKind.CLASS) {
                MenuItem deleteClass = new MenuItem("Delete Class (" + item.name + ")");
                deleteClass.setGraphic(new FontIcon("fth-trash-2"));
                deleteClass.setOnAction(e -> {
                    if (item.path != null && activeArchive() != null) {
                        service.deleteEntry(activeArchive(), item.path).thenRun(() -> Platform.runLater(() -> {
                            clearInspection();
                            hierarchy.refresh();
                        }));
                    }
                });

                MenuItem copyPath = new MenuItem("Copy Path");
                copyPath.setGraphic(new FontIcon("fth-copy"));
                copyPath.setOnAction(e -> {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(item.path != null && !item.path.isBlank() ? item.path : item.name);
                    Clipboard.getSystemClipboard().setContent(content);
                });
                cm.getItems().addAll(deleteClass, copyPath);
            } else {
                MenuItem newClass = new MenuItem("New Class…");
                newClass.setGraphic(new FontIcon("fth-file-plus"));
                newClass.setOnAction(e -> openNewClassDialog());

                MenuItem addExtra = new MenuItem("Add Extra JAR…");
                addExtra.setGraphic(new FontIcon("fth-plus-circle"));
                addExtra.setOnAction(e -> openAddExtraJarDialog());

                MenuItem copyPath = new MenuItem("Copy Path");
                copyPath.setGraphic(new FontIcon("fth-copy"));
                copyPath.setOnAction(e -> {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(item.path != null && !item.path.isBlank() ? item.path : item.name);
                    Clipboard.getSystemClipboard().setContent(content);
                });
                cm.getItems().addAll(newClass, addExtra, copyPath);
            }
            setContextMenu(cm);
        }
    }

    private record StyledSource(DecompileResult result,
                                org.fxmisc.richtext.model.StyleSpans<Collection<String>> styles) {}

    private record ClassVersionChoice(int major) {
        private String label() {
            return "Java " + javaVersion(major) + "  •  major " + major;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private enum ScanView {
        MAINS("main methods") {
            @Override String complete(ArchiveInspector.ArchiveScan scan) {
                return scan.mainMethods().size() + " public static main method(s)";
            }
        },
        STRINGS("string constants") {
            @Override String complete(ArchiveInspector.ArchiveScan scan) {
                return scan.strings().size() + " string constant occurrence(s)";
            }
        },
        CALLS("call sequences") {
            @Override String complete(ArchiveInspector.ArchiveScan scan) {
                return scan.calls().size() + " method invocation(s)";
            }
        },
        FINDINGS("capabilities") {
            @Override String complete(ArchiveInspector.ArchiveScan scan) {
                return scan.findings().size() + " capability finding(s); these are evidence, not a malware verdict";
            }
        };

        private final String label;
        ScanView(String label) { this.label = label; }
        abstract String complete(ArchiveInspector.ArchiveScan scan);
    }
}
