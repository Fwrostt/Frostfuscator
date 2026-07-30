package dev.frost.obfuscator.gui.page;

import dev.frost.graph.Graph;
import dev.frost.graph.GraphOptions;
import dev.frost.graph.TraversalDirection;
import dev.frost.graph.bytecode.BytecodeClassInfo;
import dev.frost.graph.bytecode.BytecodeMethodInfo;
import dev.frost.graph.bytecode.BytecodeProject;
import dev.frost.graph.bytecode.BytecodeProjectIndex;
import dev.frost.graph.transform.BuildExecutionSnapshot;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.graph.GraphService;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.NumericField;
import dev.frost.obfuscator.gui.component.SearchableDropdown;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.graph.GraphViewer;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.util.Logger;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Class-scoped graph analysis workbench for structure, behavior, dry runs, and completed builds. */
public final class GraphPage implements PageView {
    private enum TargetMode { NONE, CLASS, CLASS_AND_OPTIONAL_METHOD, METHOD }

    private static final Set<String> BYTECODE_TYPES = Set.of("dependencies", "calls", "inheritance", "packages", "cfg");
    private static final List<GraphChoice> GRAPH_CHOICES = List.of(
            new GraphChoice("packages", "Package overview", "Structure",
                    "See package-level coupling before drilling into individual classes.", TargetMode.NONE),
            new GraphChoice("dependencies", "Class dependencies", "Structure",
                    "Trace what one class uses and what depends on it.", TargetMode.CLASS),
            new GraphChoice("inheritance", "Inheritance", "Structure",
                    "Follow parent, interface, implementation, and subclass relationships.", TargetMode.CLASS),
            new GraphChoice("calls", "Class call flow", "Behavior",
                    "Trace calls entering the class in green and leaving it in blue.", TargetMode.CLASS),
            new GraphChoice("cfg", "Method control flow", "Behavior",
                    "Analyze branches, loops, exception paths, and exits inside one method.", TargetMode.METHOD),
            new GraphChoice("impact", "Obfuscation dry run", "Obfuscation",
                    "Preview which configured transformers can inspect a selected class without changing bytecode.", TargetMode.CLASS),
            new GraphChoice("pipeline", "Transformer pipeline", "Obfuscation",
                    "Review transformer order, phases, and required steps.", TargetMode.NONE),
            new GraphChoice("transformers", "Transformer relationships", "Obfuscation",
                    "Find dependencies and conflicts between enabled transformers.", TargetMode.NONE),
            new GraphChoice("mappings", "Name mappings", "Obfuscation",
                    "Explore original and obfuscated names from the current mapping output.", TargetMode.NONE),
            new GraphChoice("build", "Latest build execution", "Execution",
                    "Inspect duration, modifications, generated members, warnings, and verification from the latest run.", TargetMode.NONE));

    private final AppContext context;
    private final Consumer<PageId> navigation;
    private final BorderPane root = new BorderPane();
    private final StackPane viewerHost = new StackPane();
    private final CustomComboBox<GraphChoice> type = new CustomComboBox<>(GRAPH_CHOICES,
            GraphChoice::label, value -> value.category() + "  ·  " + value.label());
    private final SearchableDropdown<BytecodeClassInfo> classSelector =
            new SearchableDropdown<>("Choose a class", BytecodeClassInfo::qualifiedName);
    private final SearchableDropdown<BytecodeMethodInfo> methodSelector =
            new SearchableDropdown<>("Choose a method", BytecodeMethodInfo::displayName);
    private final CustomComboBox<TraversalDirection> traversalDirection = new CustomComboBox<>(
            List.of(TraversalDirection.OUTGOING, TraversalDirection.INCOMING, TraversalDirection.BOTH),
            value -> switch (value) {
                case OUTGOING -> "Calls from class";
                case INCOMING -> "Calls into class";
                case BOTH -> "Incoming + outgoing";
            });
    private final CustomComboBox<GraphViewer.FlowDirection> flowDirection = new CustomComboBox<>(
            List.of(GraphViewer.FlowDirection.LEFT_TO_RIGHT, GraphViewer.FlowDirection.TOP_TO_BOTTOM),
            GraphViewer.FlowDirection::label);
    private final Label typeDescription = Ui.label("", "graph-kind-description");
    private final Label scopeSummary = Ui.label("No archive indexed", "graph-scope-summary");
    private final NumericField depth = new NumericField(GraphOptions.DEFAULT_TRAVERSAL_DEPTH, 0, 12, 1,
            "levels", GraphOptions.DEFAULT_TRAVERSAL_DEPTH);
    private final NumericField maxNodes = new NumericField(GraphOptions.DEFAULT_MAXIMUM_NODES, 25, 10_000, 25,
            "nodes", GraphOptions.DEFAULT_MAXIMUM_NODES);
    private final NumericField maxEdges = new NumericField(GraphOptions.DEFAULT_MAXIMUM_EDGES, 50, 30_000, 50,
            "edges", GraphOptions.DEFAULT_MAXIMUM_EDGES);
    private final CheckBox libraries = new CheckBox("Include library classes");
    private final CheckBox fullArchive = new CheckBox("Allow archive-wide detail graphs");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label progressText = Ui.label("Indexing the current project…", "graph-progress-copy");
    private final FlowPane targetControls = new FlowPane(Ui.SPACE_3, Ui.SPACE_2);
    private final VBox classField;
    private final VBox methodField;
    private final VBox directionField;
    private final VBox depthField;
    private final MenuButton limits = new MenuButton("Limits");
    private final Button generate;
    private final Button cancel;
    private GraphViewer viewer;
    private GraphService service;
    private ExecutorService executor;
    private Future<?> task;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private BytecodeProject project;
    private BytecodeProjectIndex index;
    private Path loadedInput;
    private List<Path> loadedLibraries = List.of();
    private Graph lastGraph;
    private boolean indexing;
    private long operationVersion;
    private final ChangeListener<BuildExecutionSnapshot> buildGraphListener = (obs, old, value) -> {
        if (value != null && viewer != null && type.getValue() != null && type.getValue().id().equals("build")) generate();
    };

    public GraphPage(AppContext context, Consumer<PageId> navigation) {
        this.context = context;
        this.navigation = navigation;
        root.getStyleClass().addAll("page", "graph-page");

        configureGraphType();
        configureClassSelector();
        configureMethodSelector();
        traversalDirection.setValue(TraversalDirection.BOTH);
        traversalDirection.setPrefWidth(190);
        depth.setPrefWidth(280);
        flowDirection.setValue(GraphViewer.FlowDirection.LEFT_TO_RIGHT);
        flowDirection.setPrefWidth(150);
        flowDirection.getStyleClass().add("graph-flow-direction");
        flowDirection.valueProperty().addListener((obs, old, value) -> {
            if (viewer != null) viewer.setFlowDirection(value);
        });

        classField = compactField("Class", classSelector);
        methodField = compactField("Method", methodSelector);
        directionField = compactField("Connections", traversalDirection);
        depthField = compactField("Depth", depth);

        generate = Ui.button("Analyze", "primary-button", this::generate);
        generate.getStyleClass().add("graph-analyze-button");
        generate.setDefaultButton(true);
        generate.setMinWidth(102);
        cancel = Ui.button("Cancel", "secondary-button", this::cancel);
        cancel.setVisible(false);
        cancel.setManaged(false);

        progress.setPrefSize(15, 15);
        progress.setMaxSize(15, 15);
        progress.setVisible(false);
        progress.setManaged(false);

        configureLimitsMenu();
        libraries.setOnAction(ignored -> invalidateAndIndex());
        fullArchive.setOnAction(ignored -> updateGraphChoice(type.getValue()));

        VBox header = new VBox(Ui.SPACE_4,
                Ui.pageHeader("Graphs", "Trace structure, method flow, and obfuscation impact without loading the entire project into one diagram."),
                setupPanel());
        header.getStyleClass().add("graph-page-header");
        header.setPadding(new Insets(Ui.SPACE_6, Ui.SPACE_8, Ui.SPACE_4, Ui.SPACE_8));
        root.setTop(header);

        viewerHost.getStyleClass().add("graph-viewer-host");
        viewerHost.setMinSize(0, 330);
        root.setCenter(viewerHost);
        BorderPane.setMargin(viewerHost, new Insets(0, Ui.SPACE_8, Ui.SPACE_8, Ui.SPACE_8));

        String remembered = context.preferences().get("graphs.type", "packages");
        type.setValue(GRAPH_CHOICES.stream().filter(choice -> choice.id().equals(remembered))
                .findFirst().orElse(GRAPH_CHOICES.getFirst()));
        updateGraphChoice(type.getValue());
    }

    private void configureGraphType() {
        type.getStyleClass().add("graph-analysis-selector");
        type.setPrefWidth(260);
        type.setMinWidth(230);
        type.valueProperty().addListener((obs, old, value) -> updateGraphChoice(value));
    }

    private void configureClassSelector() {
        classSelector.getStyleClass().add("graph-class-selector");
        classSelector.setPrefWidth(350);
        classSelector.setMinWidth(250);
        classSelector.setMaxWidth(Double.MAX_VALUE);
        classSelector.valueProperty().addListener((obs, old, value) -> {
            methodSelector.setValues(value == null ? List.of() : value.methods());
            methodSelector.setValue(null);
            updateGraphChoice(type.getValue());
        });
    }

    private void configureMethodSelector() {
        methodSelector.getStyleClass().add("graph-method-selector");
        methodSelector.setPrefWidth(310);
        methodSelector.setMinWidth(230);
        methodSelector.setMaxWidth(Double.MAX_VALUE);
        methodSelector.valueProperty().addListener((obs, old, value) -> updateScopeSummary());
    }

    private void configureLimitsMenu() {
        VBox content = new VBox(Ui.SPACE_3,
                Ui.label("Graph safety limits", "field-label"),
                compactField("Maximum nodes", maxNodes), compactField("Maximum edges", maxEdges),
                new Separator(), libraries, fullArchive,
                Ui.label("Focused graphs are projected before these limits are applied.", "graph-limit-help"));
        content.getStyleClass().add("graph-limits-panel");
        CustomMenuItem item = new CustomMenuItem(content, false);
        item.setHideOnClick(false);
        item.getStyleClass().add("graph-limits-menu-item");
        limits.getItems().add(item);
        limits.getStyleClass().addAll("secondary-button", "graph-limits-button");
        limits.setMinWidth(96);
    }

    private Node setupPanel() {
        VBox graphField = compactField("Analysis", type);
        typeDescription.setWrapText(true);
        typeDescription.setMaxWidth(620);
        typeDescription.setMinWidth(180);
        HBox.setHgrow(typeDescription, Priority.ALWAYS);
        HBox primary = new HBox(Ui.SPACE_3, graphField, typeDescription);
        primary.getStyleClass().add("graph-primary-row");
        primary.setAlignment(Pos.BOTTOM_LEFT);
        primary.setMinWidth(0);

        targetControls.getStyleClass().add("graph-target-controls");
        targetControls.setAlignment(Pos.BOTTOM_LEFT);
        targetControls.getChildren().addAll(classField, methodField, directionField, depthField,
                compactField("Flow", flowDirection), compactField("Safety", limits));
        HBox.setHgrow(targetControls, Priority.ALWAYS);
        HBox actions = new HBox(Ui.SPACE_2, generate, cancel);
        actions.getStyleClass().add("graph-analysis-actions");
        actions.setAlignment(Pos.BOTTOM_RIGHT);
        HBox scopeRow = new HBox(Ui.SPACE_4, targetControls, actions);
        scopeRow.getStyleClass().add("graph-scope-row");
        scopeRow.setAlignment(Pos.BOTTOM_LEFT);

        HBox status = new HBox(Ui.SPACE_2, progress, progressText, Ui.spacer(), scopeSummary);
        status.getStyleClass().add("graph-generation-status");
        status.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(Ui.SPACE_3, primary, scopeRow, status);
        panel.getStyleClass().add("graph-setup-panel");
        return panel;
    }

    private static VBox compactField(String label, Node control) {
        VBox field = new VBox(Ui.SPACE_1, Ui.label(label, "field-label"), control);
        field.getStyleClass().add("graph-compact-field");
        return field;
    }

    @Override public Node root() { return root; }

    @Override public void onShown() {
        if (viewer != null) return;
        service = new GraphService();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "frost-graph-analysis");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        try {
            viewer = new GraphViewer(context.stage(), context.themeManager());
            viewer.setRefreshAction(this::generate);
            viewer.setFlowDirection(flowDirection.getValue());
            viewer.setOpenAction(node -> {
                String owner = node.metadata().string("internalName", node.metadata().string("owner", ""));
                if (!owner.isBlank()) context.projectState().requestGraphClassOpen(owner);
                navigation.accept(PageId.BYTECODE);
            });
            viewerHost.getChildren().setAll(viewer);
            context.projectState().buildGraphProperty().addListener(buildGraphListener);
            refreshIndex();
        } catch (Throwable failure) {
            Logger.error("Graph viewer could not initialize", failure);
            releaseInfrastructure();
            Label explanation = Ui.label(
                    "The embedded renderer is unavailable in this Java runtime. The rest of Frostfuscator can continue normally.",
                    "section-description");
            explanation.setWrapText(true);
            Button retry = Ui.button("Retry", "secondary-button", () -> {
                viewerHost.getChildren().clear();
                onShown();
            });
            VBox fallback = new VBox(Ui.SPACE_4,
                    Ui.section("Graph viewer unavailable", "Launch the complete GUI package, then retry.", explanation, retry));
            fallback.setPadding(new Insets(Ui.SPACE_6, 0, 0, 0));
            viewerHost.getChildren().setAll(fallback);
        }
    }

    @Override public void onHidden() {
        cancel();
        context.projectState().buildGraphProperty().removeListener(buildGraphListener);
        if (viewer != null) viewer.close();
        viewer = null;
        viewerHost.getChildren().clear();
        project = null;
        index = null;
        loadedInput = null;
        loadedLibraries = List.of();
        lastGraph = null;
        releaseInfrastructure();
    }

    private void refreshIndex() {
        if (executor == null || indexing || (task != null && !task.isDone())) return;
        final ObfuscationConfig config;
        final Path input;
        final List<Path> libraryPaths;
        try {
            config = context.configurationBinder().snapshot();
            input = input(config);
            libraryPaths = libraries.isSelected() ? libraryPaths(config) : List.of();
        } catch (RuntimeException exception) {
            classSelector.setDisable(true);
            methodSelector.setDisable(true);
            progressText.setText(exception.getMessage());
            scopeSummary.setText("Choose an input JAR to browse classes");
            updateAnalyzeAvailability();
            return;
        }
        if (input.equals(loadedInput) && libraryPaths.equals(loadedLibraries) && index != null) {
            applyIndex(index);
            return;
        }
        indexing = true;
        setBusy(true, "Indexing classes and methods…");
        long operation = ++operationVersion;
        task = executor.submit(() -> {
            try {
                BytecodeProject loaded = service.load(input, libraryPaths);
                BytecodeProjectIndex loadedIndex = service.index(loaded);
                Platform.runLater(() -> {
                    if (operation != operationVersion || viewer == null) return;
                    project = loaded;
                    index = loadedIndex;
                    loadedInput = input;
                    loadedLibraries = List.copyOf(libraryPaths);
                    indexing = false;
                    applyIndex(loadedIndex);
                    setBusy(false, "Project index ready.");
                });
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    if (operation != operationVersion || viewer == null) return;
                    indexing = false;
                    setBusy(false, "Could not index project: " + friendlyMessage(exception));
                });
            }
        });
    }

    private void applyIndex(BytecodeProjectIndex loadedIndex) {
        BytecodeClassInfo selected = classSelector.getValue();
        classSelector.setValues(loadedIndex.classes().stream().filter(item -> !item.library()).toList());
        if (selected != null) classSelector.setValue(loadedIndex.findClass(selected.internalName()).orElse(null));
        classSelector.setDisable(false);
        updateGraphChoice(type.getValue());
        updateScopeSummary();
    }

    private void invalidateAndIndex() {
        project = null;
        index = null;
        loadedInput = null;
        loadedLibraries = List.of();
        classSelector.setValues(List.of());
        methodSelector.setValues(List.of());
        refreshIndex();
    }

    private void generate() {
        if (viewer == null || executor == null || indexing || progress.isVisible()) return;
        GraphChoice choice = type.getValue();
        if (choice == null) return;
        if (choice.id().equals("build") && context.projectState().buildGraph() == null) {
            progressText.setText(context.projectState().busyProperty().get()
                    ? "Build is running. This view will update as soon as the execution snapshot is ready."
                    : "Run a build first, then return here to inspect its execution.");
            return;
        }

        BytecodeClassInfo selectedClass = classSelector.getValue();
        BytecodeMethodInfo selectedMethod = methodSelector.getValue();
        if (requiresClass(choice) && selectedClass == null && !archiveWideAllowed(choice)) {
            progressText.setText("Choose a class to keep this analysis focused.");
            classSelector.requestFocus();
            classSelector.show();
            return;
        }
        if (choice.targetMode() == TargetMode.METHOD && selectedMethod == null) {
            progressText.setText("Choose a method for the control-flow analysis.");
            methodSelector.requestFocus();
            methodSelector.show();
            return;
        }

        final ObfuscationConfig config;
        final GraphOptions options;
        try {
            config = context.configurationBinder().snapshot();
            String focus = focus(choice, selectedClass, selectedMethod);
            options = new GraphOptions(maxNodes.getValue(), maxEdges.getValue(), depth.getValue(),
                    libraries.isSelected(), false, false, Set.of(), Set.of(), focus, traversalDirection.getValue());
        } catch (RuntimeException exception) {
            progressText.setText(exception.getMessage());
            return;
        }

        if ((BYTECODE_TYPES.contains(choice.id()) || choice.id().equals("impact")) && project == null) {
            progressText.setText("The project index is not ready yet.");
            refreshIndex();
            return;
        }

        String requestedClass = selectedClass == null ? null : selectedClass.internalName();
        String requestedMethod = selectedMethod == null ? null : selectedMethod.name();
        String requestedDescriptor = selectedMethod == null ? null : selectedMethod.descriptor();
        BuildExecutionSnapshot buildSnapshot = context.projectState().buildGraph();
        context.preferences().put("graphs.type", choice.id());
        cancelled = new AtomicBoolean();
        setBusy(true, "Analyzing " + choice.label().toLowerCase() + "…");

        AtomicBoolean requestCancellation = cancelled;
        long operation = ++operationVersion;
        task = executor.submit(() -> {
            try {
                Graph graph;
                if (choice.id().equals("build")) {
                    graph = service.completedBuildGraph(buildSnapshot, options);
                } else if (choice.id().equals("impact")) {
                    graph = service.obfuscationPreviewGraph(project, requestedClass, config, null, options);
                } else if (!BYTECODE_TYPES.contains(choice.id())) {
                    graph = service.transformerGraph(choice.id(), config, null, options);
                } else {
                    graph = service.bytecodeGraph(choice.id(), project, requestedClass, requestedMethod,
                            requestedDescriptor, options, requestCancellation::get,
                            (done, total, message) -> Platform.runLater(() ->
                                    progressText.setText(message + "  ·  " + done + "/" + total)));
                }
                Platform.runLater(() -> {
                    if (operation != operationVersion || viewer == null) return;
                    if (viewer != null) {
                        viewer.setComparison(lastGraph != null && lastGraph.type() == graph.type() ? lastGraph : null);
                        viewer.setGraph(graph);
                        lastGraph = graph;
                    }
                    setBusy(false, resultMessage(graph));
                });
            } catch (CancellationException ignored) {
                Platform.runLater(() -> {
                    if (operation == operationVersion && viewer != null) setBusy(false, "Analysis cancelled.");
                });
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    if (operation == operationVersion && viewer != null)
                        setBusy(false, "Could not analyze graph: " + friendlyMessage(exception));
                });
            }
        });
    }

    private String focus(GraphChoice choice, BytecodeClassInfo selectedClass, BytecodeMethodInfo selectedMethod) {
        if (fullArchive.isSelected() && archiveWideAllowed(choice)) return null;
        if (choice.id().equals("cfg") || choice.id().equals("impact") || choice.id().equals("packages")) return null;
        return selectedClass == null ? null : selectedClass.internalName();
    }

    private boolean requiresClass(GraphChoice choice) {
        return choice.targetMode() == TargetMode.CLASS || choice.targetMode() == TargetMode.CLASS_AND_OPTIONAL_METHOD
                || choice.targetMode() == TargetMode.METHOD;
    }

    private boolean archiveWideAllowed(GraphChoice choice) {
        return fullArchive.isSelected() && (choice.id().equals("dependencies")
                || choice.id().equals("inheritance") || choice.id().equals("calls"));
    }

    private void cancel() {
        operationVersion++;
        cancelled.set(true);
        if (task != null) task.cancel(true);
        task = null;
        if (progress.isVisible()) setBusy(false, "Analysis cancelled.");
        indexing = false;
    }

    private void setBusy(boolean busy, String message) {
        progress.setVisible(busy);
        progress.setManaged(busy);
        cancel.setVisible(busy);
        cancel.setManaged(busy);
        generate.setDisable(busy);
        type.setDisable(busy);
        classSelector.setDisable(busy || index == null);
        methodSelector.setDisable(busy || classSelector.getValue() == null);
        progressText.setText(message);
        if (!busy) updateAnalyzeAvailability();
    }

    private void updateGraphChoice(GraphChoice choice) {
        if (choice == null) return;
        typeDescription.setText(choice.description());
        boolean needsClass = requiresClass(choice);
        boolean methodVisible = choice.targetMode() == TargetMode.METHOD
                || choice.targetMode() == TargetMode.CLASS_AND_OPTIONAL_METHOD;
        boolean relation = choice.id().equals("dependencies") || choice.id().equals("inheritance")
                || choice.id().equals("calls");
        classField.setVisible(needsClass);
        classField.setManaged(needsClass);
        methodField.setVisible(methodVisible);
        methodField.setManaged(methodVisible);
        directionField.setVisible(relation);
        directionField.setManaged(relation);
        depthField.setVisible(relation);
        depthField.setManaged(relation);
        libraries.setVisible(BYTECODE_TYPES.contains(choice.id()) || choice.id().equals("impact"));
        libraries.setManaged(libraries.isVisible());
        fullArchive.setVisible(relation);
        fullArchive.setManaged(relation);
        classSelector.setDisable(index == null || archiveWideAllowed(choice));
        methodSelector.setDisable(classSelector.getValue() == null || !methodVisible || archiveWideAllowed(choice));
        updateScopeSummary();
        updateAnalyzeAvailability();
    }

    private void updateAnalyzeAvailability() {
        GraphChoice choice = type.getValue();
        if (choice == null || generate == null) return;
        boolean needsIndex = BYTECODE_TYPES.contains(choice.id()) || choice.id().equals("impact");
        generate.setDisable(indexing || progress.isVisible() || (needsIndex && index == null));
    }

    private void updateScopeSummary() {
        if (index == null) {
            scopeSummary.setText("No archive indexed");
            updateAnalyzeAvailability();
            return;
        }
        BytecodeClassInfo selectedClass = classSelector.getValue();
        BytecodeMethodInfo selectedMethod = methodSelector.getValue();
        if (archiveWideAllowed(type.getValue())) {
            scopeSummary.setText("Archive-wide  ·  limited to " + maxNodes.getValue() + " nodes");
        } else if (selectedMethod != null && type.getValue().targetMode() == TargetMode.METHOD) {
            scopeSummary.setText(selectedMethod.displayName() + "  ·  " + selectedClass.qualifiedName());
        } else if (selectedClass != null) {
            scopeSummary.setText(selectedClass.qualifiedName() + "  ·  " + selectedClass.methods().size() + " methods");
        } else {
            scopeSummary.setText(index.classes().stream().filter(item -> !item.library()).count() + " project classes indexed");
        }
        updateAnalyzeAvailability();
    }

    private static String resultMessage(Graph graph) {
        if (graph.nodes().isEmpty()) return "No relationships matched this scope. Try both directions or increase depth.";
        String message = "Ready  ·  " + graph.nodes().size() + " nodes  ·  " + graph.edges().size() + " edges";
        if (graph.truncated()) message += "  ·  limited";
        if (!graph.warnings().isEmpty()) message += "  ·  " + graph.warnings().size() + " warnings";
        return message;
    }

    private void releaseInfrastructure() {
        if (service != null) service.clearCache();
        service = null;
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private static String friendlyMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.replace("--class is required for cfg graphs", "Choose a class for the control-flow graph")
                .replace("--method is required for cfg graphs", "Choose a method for the control-flow graph")
                .replace("Method is overloaded; provide --descriptor", "Choose the exact overloaded method from the method list");
    }

    private static Path input(ObfuscationConfig config) {
        if (config.getInput() == null || config.getInput().isBlank())
            throw new IllegalArgumentException("Choose an input JAR on the Input page first.");
        return Path.of(config.getInput()).toAbsolutePath().normalize();
    }

    private static List<Path> libraryPaths(ObfuscationConfig config) {
        List<Path> paths = new ArrayList<>();
        if (config.getLibs() != null && !config.getLibs().isBlank()) paths.add(Path.of(config.getLibs()));
        for (String value : config.getLibraries().getPaths())
            if (value != null && !value.isBlank()) paths.add(Path.of(value));
        return paths.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    private record GraphChoice(String id, String label, String category, String description, TargetMode targetMode) { }
}
