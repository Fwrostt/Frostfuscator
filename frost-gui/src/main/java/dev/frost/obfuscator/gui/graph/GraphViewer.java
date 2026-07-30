package dev.frost.obfuscator.gui.graph;

import dev.frost.graph.EdgeType;
import dev.frost.graph.Graph;
import dev.frost.graph.GraphEdge;
import dev.frost.graph.GraphMetadata;
import dev.frost.graph.GraphNode;
import dev.frost.graph.GraphType;
import dev.frost.graph.NodeType;
import dev.frost.graph.export.GraphExporters;
import dev.frost.graph.export.JsonGraphExporter;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.theme.ThemeDefinition;
import dev.frost.obfuscator.gui.theme.ThemeManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Scalable Cytoscape-backed graph workbench with filtering, layouts, and semantic inspection. */
public final class GraphViewer extends BorderPane implements AutoCloseable {
    public enum LayoutMode {
        AUTOMATIC("Automatic"), DIRECTIONAL("Directional"), FORCE("Force"), RADIAL("Radial"), GRID("Grid");
        private final String label;
        LayoutMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum FlowDirection {
        LEFT_TO_RIGHT("Left to right", "rightward"), TOP_TO_BOTTOM("Top to bottom", "downward");
        private final String label;
        private final String rendererValue;
        FlowDirection(String label, String rendererValue) {
            this.label = label;
            this.rendererValue = rendererValue;
        }
        public String label() { return label; }
        String rendererValue() { return rendererValue; }
    }

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final String CYTOSCAPE_RESOURCE =
            "/META-INF/resources/webjars/cytoscape/3.33.4/dist/cytoscape.min.js";

    private final Window owner;
    private final ThemeManager themes;
    private final WebView web = new WebView();
    private final StackPane canvas = new StackPane();
    private final VBox emptyState = new VBox(Ui.SPACE_3);
    private final javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane();
    private final VBox inspector = new VBox(Ui.SPACE_2);
    private final VBox inspectorFacts = new VBox(Ui.SPACE_3);
    private final Label inspectorTitle = Ui.label("Selection details", "graph-inspector-title");
    private final Label inspectorKind = Ui.label("Select a node to inspect it.", "graph-inspector-kind");
    private final ListView<GraphEdge> connectedEdges = new ListView<>();
    private final TextField search = new TextField();
    private final javafx.scene.control.ComboBox<NodeType> nodeType = new javafx.scene.control.ComboBox<>();
    private final javafx.scene.control.ComboBox<EdgeType> edgeType = new javafx.scene.control.ComboBox<>();
    private final CheckBox hideLibraries = new CheckBox("Hide library nodes");
    private final CheckBox hideIsolated = new CheckBox("Hide isolated nodes");
    private final MenuButton filters = new MenuButton("Filters");
    private final MenuButton layout = new MenuButton("Layout");
    private final Button detailsToggle = button("Details", () -> setInspectorVisible(!isInspectorVisible()));
    private final Button openButton = button("Open in Bytecode", this::openSelected);
    private final Button compareButton = button("Compare runs", this::showComparison);
    private final Label status = Ui.label("No graph loaded", "graph-status-copy");
    private final Label navigationHint = Ui.label("Drag to pan  ·  Scroll to zoom  ·  Select a node to inspect",
            "graph-navigation-hint");
    private final Timeline rendererPoll;
    private final ChangeListener<ThemeDefinition> themeListener = (obs, old, value) -> refreshTheme();
    private final ChangeListener<String> accentListener = (obs, old, value) -> refreshTheme();
    private Graph source;
    private Graph comparison;
    private Runnable refreshAction = () -> { };
    private Consumer<GraphNode> openAction = ignored -> { };
    private String selectedId = "";
    private LayoutMode layoutMode = LayoutMode.AUTOMATIC;
    private FlowDirection flowDirection = FlowDirection.LEFT_TO_RIGHT;
    private boolean disposed;
    private boolean updatingFilters;
    private double inspectorDivider = 0.76;

    public GraphViewer(Window owner, ThemeManager themes) {
        this.owner = owner;
        this.themes = themes;
        getStyleClass().add("graph-viewer");
        setMinSize(0, 0);
        setTop(commandBar());

        web.getStyleClass().add("graph-web-view");
        web.setContextMenuEnabled(false);
        web.getEngine().setJavaScriptEnabled(true);
        web.setMinSize(0, 0);
        emptyState.getStyleClass().add("graph-empty-state");
        emptyState.setAlignment(Pos.CENTER);
        Label emptyTitle = Ui.label("Choose a focused analysis", "graph-empty-title");
        Label emptyCopy = Ui.label(
                "Select a graph and a class above. Frostfuscator will load only the neighborhood needed to answer your question.",
                "graph-empty-copy");
        emptyCopy.setWrapText(true);
        emptyCopy.setMaxWidth(560);
        Label emptyHint = Ui.label("Start with Package overview for the archive, or Class dependencies for a focused trace.",
                "graph-empty-hint");
        emptyState.getChildren().addAll(emptyTitle, emptyCopy, emptyHint);
        canvas.getStyleClass().add("graph-canvas");
        canvas.getChildren().addAll(web, emptyState);

        configureInspector();
        split.getStyleClass().add("graph-split");
        split.getItems().add(canvas);
        setCenter(split);

        HBox footer = new HBox(Ui.SPACE_3, status, Ui.spacer(), navigationHint);
        footer.getStyleClass().add("graph-footer");
        footer.setAlignment(Pos.CENTER_LEFT);
        setBottom(footer);

        themes.activeThemeProperty().addListener(themeListener);
        themes.accentProperty().addListener(accentListener);
        refreshTheme();
        rendererPoll = new Timeline(new KeyFrame(Duration.millis(180), ignored -> pollRenderer()));
        rendererPoll.setCycleCount(Timeline.INDEFINITE);
        rendererPoll.play();
    }

    public void setRefreshAction(Runnable action) {
        refreshAction = action == null ? () -> { } : action;
    }

    public void setOpenAction(Consumer<GraphNode> action) {
        openAction = action == null ? ignored -> { } : action;
    }

    public void setComparison(Graph graph) {
        comparison = graph;
        compareButton.setDisable(graph == null || source == null);
    }

    public void setFlowDirection(FlowDirection direction) {
        flowDirection = direction == null ? FlowDirection.LEFT_TO_RIGHT : direction;
        runLayout();
    }

    public void setGraph(Graph graph) {
        source = graph;
        selectedId = "";
        showPlaceholderInspector();
        connectedEdges.getItems().clear();
        openButton.setDisable(true);
        compareButton.setDisable(comparison == null || graph == null);
        detailsToggle.setDisable(graph == null);

        List<NodeType> nodeTypes = graph == null ? List.of()
                : graph.nodes().stream().map(GraphNode::type).distinct().sorted().toList();
        List<EdgeType> edgeTypes = graph == null ? List.of()
                : graph.edges().stream().map(GraphEdge::type).distinct().sorted().toList();
        updatingFilters = true;
        try {
            nodeType.getItems().setAll(nodeTypes);
            edgeType.getItems().setAll(edgeTypes);
            nodeType.getSelectionModel().clearSelection();
            edgeType.getSelectionModel().clearSelection();
        } finally {
            updatingFilters = false;
        }
        emptyState.setVisible(graph == null);
        emptyState.setManaged(graph == null);
        updateFilterCount();
        if (graph == null) refreshTheme(); else render();
    }

    private Node commandBar() {
        search.setPromptText("Find a node…");
        search.setAccessibleText("Find a node in the current graph");
        search.setMinWidth(125);
        search.setPrefWidth(180);
        search.setMaxWidth(280);
        search.textProperty().addListener((obs, old, value) -> execute("window.frostSearch(" + jsString(value) + ")"));

        configureTypeCombo(nodeType, "All node types");
        configureTypeCombo(edgeType, "All edge types");
        nodeType.valueProperty().addListener((obs, old, value) -> filtersChanged());
        edgeType.valueProperty().addListener((obs, old, value) -> filtersChanged());
        hideLibraries.setOnAction(ignored -> filtersChanged());
        hideIsolated.setOnAction(ignored -> filtersChanged());
        Button clearFilters = Ui.button("Clear filters", "inline-button", this::clearFilters);
        VBox filterPanel = new VBox(Ui.SPACE_3, Ui.label("Visible relationships", "field-label"), nodeType, edgeType,
                new Separator(), hideLibraries, hideIsolated, clearFilters);
        filterPanel.getStyleClass().add("graph-filter-panel");
        CustomMenuItem filterContent = new CustomMenuItem(filterPanel, false);
        filterContent.setHideOnClick(false);
        filters.getItems().add(filterContent);
        filters.getStyleClass().add("secondary-button");
        filters.setMinWidth(76);

        for (LayoutMode mode : LayoutMode.values()) {
            MenuItem item = new MenuItem(mode.label());
            item.setOnAction(ignored -> {
                layoutMode = mode;
                layout.setText(mode == LayoutMode.AUTOMATIC ? "Layout" : mode.label());
                runLayout();
            });
            layout.getItems().add(item);
        }
        layout.getStyleClass().add("secondary-button");
        layout.setMinWidth(90);
        layout.setPrefWidth(90);

        Button zoomOut = compactButton("−", "Zoom out", () -> execute("window.frostZoom(0.82)"));
        Button zoomIn = compactButton("+", "Zoom in", () -> execute("window.frostZoom(1.22)"));
        Button fit = compactButton("Fit", "Fit graph to canvas", () -> execute("window.frostFit()"));
        fit.getStyleClass().add("graph-zoom-last");
        HBox zoom = new HBox(0, zoomOut, zoomIn, fit);
        zoom.getStyleClass().add("graph-zoom-group");
        Button refresh = button("Refresh", refreshAction::run);
        refresh.getStyleClass().addAll("secondary-button", "graph-refresh-button");

        MenuButton export = new MenuButton("Export");
        export.getStyleClass().add("secondary-button");
        export.setMinWidth(84);
        MenuItem copy = new MenuItem("Copy JSON");
        copy.setOnAction(ignored -> copyJson());
        export.getItems().add(copy);
        export.getItems().add(new SeparatorMenuItem());
        for (String format : List.of("json", "dot", "mermaid", "png")) {
            MenuItem item = new MenuItem(format.equals("mermaid") ? "Mermaid source" : format.toUpperCase(Locale.ROOT));
            item.setOnAction(ignored -> export(format));
            export.getItems().add(item);
        }

        detailsToggle.getStyleClass().add("secondary-button");
        detailsToggle.setDisable(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(Ui.SPACE_2, search, layout, filters, zoom, refresh, spacer, detailsToggle, export);
        bar.getStyleClass().add("graph-command-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMinWidth(0);
        refresh.managedProperty().bind(bar.widthProperty().greaterThanOrEqualTo(820));
        refresh.visibleProperty().bind(refresh.managedProperty());
        zoomOut.managedProperty().bind(bar.widthProperty().greaterThanOrEqualTo(740));
        zoomOut.visibleProperty().bind(zoomOut.managedProperty());
        zoomIn.managedProperty().bind(bar.widthProperty().greaterThanOrEqualTo(740));
        zoomIn.visibleProperty().bind(zoomIn.managedProperty());
        return bar;
    }

    private void configureInspector() {
        inspector.getStyleClass().add("graph-inspector");
        inspector.setMinWidth(270);
        inspector.setPrefWidth(340);
        inspector.setMaxWidth(440);

        Button close = compactButton("×", "Close details", () -> setInspectorVisible(false));
        close.getStyleClass().add("graph-inspector-close");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox header = new HBox(Ui.SPACE_2, inspectorTitle, titleSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        inspectorKind.setWrapText(true);

        ScrollPane facts = new ScrollPane(inspectorFacts);
        facts.getStyleClass().add("graph-inspector-scroll");
        facts.setFitToWidth(true);
        facts.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        facts.setMinHeight(36);
        facts.setPrefHeight(150);
        VBox.setVgrow(facts, Priority.ALWAYS);

        connectedEdges.setPlaceholder(Ui.label("No connected edges", "graph-inspector-kind"));
        connectedEdges.setMinHeight(44);
        connectedEdges.setPrefHeight(70);
        connectedEdges.setMaxHeight(96);
        connectedEdges.setCellFactory(ignored -> edgeCell());
        connectedEdges.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (value != null) showEdge(value);
        });

        openButton.getStyleClass().add("primary-button");
        compareButton.getStyleClass().add("secondary-button");
        openButton.setDisable(true);
        compareButton.setDisable(true);
        HBox actions = new HBox(Ui.SPACE_2, openButton, compareButton);
        inspector.getChildren().addAll(header, inspectorKind, facts,
                Ui.label("Connected edges", "field-label"), connectedEdges, actions);
    }

    private <T> void configureTypeCombo(javafx.scene.control.ComboBox<T> combo, String prompt) {
        combo.setPromptText(prompt);
        combo.setPrefWidth(240);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(T value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : pretty(value.toString()));
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(T value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? prompt : pretty(value.toString()));
            }
        });
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(ignored -> action.run());
        return button;
    }

    private static Button compactButton(String text, String tooltip, Runnable action) {
        Button button = button(text, action);
        button.getStyleClass().add("graph-compact-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        return button;
    }

    private void filtersChanged() {
        if (updatingFilters) return;
        updateFilterCount();
        render();
    }

    private void clearFilters() {
        updatingFilters = true;
        try {
            nodeType.getSelectionModel().clearSelection();
            edgeType.getSelectionModel().clearSelection();
            hideLibraries.setSelected(false);
            hideIsolated.setSelected(false);
        } finally {
            updatingFilters = false;
        }
        updateFilterCount();
        render();
    }

    private void updateFilterCount() {
        int count = (nodeType.getValue() == null ? 0 : 1) + (edgeType.getValue() == null ? 0 : 1)
                + (hideLibraries.isSelected() ? 1 : 0) + (hideIsolated.isSelected() ? 1 : 0);
        filters.setText(count == 0 ? "Filters" : "Filters · " + count);
        filters.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), count > 0);
    }

    private void render() {
        if (disposed || source == null) return;
        Graph graph = filtered();
        try {
            Palette palette = palette();
            String runtime = resource(CYTOSCAPE_RESOURCE);
            String json = new JsonGraphExporter().export(graph);
            String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            web.getEngine().loadContent(html(runtime, encoded, palette, preferredLayout(graph), flowDirection));
            status.setText(graph.title() + "  ·  " + graph.nodes().size() + " nodes  ·  " + graph.edges().size() + " edges"
                    + (graph.truncated() ? "  ·  limited" : "")
                    + (graph.warnings().isEmpty() ? "" : "  ·  " + graph.warnings().size() + " warnings"));
        } catch (Exception exception) {
            showRenderError(exception.getMessage());
        }
    }

    private Graph filtered() {
        NodeType selectedNodeType = nodeType.getValue();
        EdgeType selectedEdgeType = edgeType.getValue();
        List<GraphNode> nodes = source.nodes().stream()
                .filter(node -> selectedNodeType == null || node.type() == selectedNodeType)
                .filter(node -> !hideLibraries.isSelected()
                        || (node.type() != NodeType.LIBRARY_CLASS && !node.metadata().bool("library", false))).toList();
        Set<String> ids = new HashSet<>();
        nodes.forEach(node -> ids.add(node.id()));
        List<GraphEdge> edges = source.edges().stream()
                .filter(edge -> ids.contains(edge.source()) && ids.contains(edge.target()))
                .filter(edge -> selectedEdgeType == null || edge.type() == selectedEdgeType).toList();
        if (hideIsolated.isSelected()) {
            Set<String> connected = new HashSet<>();
            edges.forEach(edge -> { connected.add(edge.source()); connected.add(edge.target()); });
            nodes = nodes.stream().filter(node -> connected.contains(node.id())).toList();
        }
        return new Graph(source.id(), source.title(), source.type(), nodes, edges,
                source.metadata(), source.warnings(), source.truncated());
    }

    private void pollRenderer() {
        if (disposed || source == null || web.getEngine().getLoadWorker().isRunning()) return;
        try {
            Object error = web.getEngine().executeScript("window.frostError || ''");
            if (error != null && !String.valueOf(error).isBlank()) {
                showRenderError(String.valueOf(error));
                return;
            }
            String renderer = String.valueOf(web.getEngine().executeScript("window.frostSelected || ''"));
            if (renderer.equals(selectedId)) return;
            selectedId = renderer;
            if (renderer.isBlank()) return;
            source.nodes().stream().filter(node -> node.id().equals(renderer)).findFirst().ifPresent(this::showNode);
        } catch (RuntimeException ignored) { }
    }

    private void showNode(GraphNode node) {
        inspectorTitle.setText(node.label());
        inspectorKind.setText(pretty(node.type().name()));
        inspectorFacts.getChildren().setAll(fact("Identifier", node.id()));
        node.metadata().values().forEach((key, value) -> inspectorFacts.getChildren().add(fact(pretty(key), value)));
        inspector.setUserData(node);
        connectedEdges.getItems().setAll(source.edges().stream()
                .filter(edge -> edge.source().equals(node.id()) || edge.target().equals(node.id())).toList());
        connectedEdges.getSelectionModel().clearSelection();
        openButton.setDisable(node.metadata().string("internalName", node.metadata().string("owner", "")).isBlank());
        setInspectorVisible(true);
    }

    private void showEdge(GraphEdge edge) {
        inspectorTitle.setText(pretty(edge.type().name()));
        inspectorKind.setText("Directed edge");
        inspectorFacts.getChildren().setAll(fact("From", labelFor(edge.source())), fact("To", labelFor(edge.target())));
        if (!edge.label().isBlank()) inspectorFacts.getChildren().add(fact("Label", edge.label()));
        edge.metadata().values().forEach((key, value) -> inspectorFacts.getChildren().add(fact(pretty(key), value)));
        inspector.setUserData(null);
        openButton.setDisable(true);
    }

    private Node fact(String key, Object value) {
        Label label = Ui.label(key, "graph-fact-label");
        Label content = Ui.label(formatValue(value), "graph-fact-value");
        content.setWrapText(true);
        VBox row = new VBox(Ui.SPACE_1, label, content);
        row.getStyleClass().add("graph-fact");
        return row;
    }

    private String labelFor(String id) {
        return source.nodes().stream().filter(node -> node.id().equals(id)).map(GraphNode::label).findFirst().orElse(id);
    }

    private ListCell<GraphEdge> edgeCell() {
        return new ListCell<>() {
            @Override protected void updateItem(GraphEdge edge, boolean empty) {
                super.updateItem(edge, empty);
                if (empty || edge == null) { setText(null); return; }
                String arrow = selectedId.equals(edge.target()) ? "← " : "→ ";
                String other = selectedId.equals(edge.target()) ? labelFor(edge.source()) : labelFor(edge.target());
                setText(arrow + other + "  ·  " + pretty(edge.type().name()));
            }
        };
    }

    private void showPlaceholderInspector() {
        inspectorTitle.setText("Selection details");
        inspectorKind.setText("Select a node to inspect its role, metadata, and connected edges.");
        inspectorFacts.getChildren().setAll(fact("Tip", "Double-click a node to center its immediate neighborhood."));
        inspector.setUserData(null);
    }

    private void showRenderError(String message) {
        status.setText("Graph renderer could not start: " + message);
        inspectorTitle.setText("Renderer unavailable");
        inspectorKind.setText("The graph data is intact and can still be exported as JSON or DOT.");
        inspectorFacts.getChildren().setAll(fact("Error", message));
        setInspectorVisible(true);
    }

    private void setInspectorVisible(boolean visible) {
        if (visible == isInspectorVisible()) return;
        if (visible) {
            split.getItems().add(inspector);
            split.setDividerPositions(inspectorDivider);
        } else {
            if (split.getDividers().size() == 1) inspectorDivider = Math.max(0.58,
                    Math.min(0.84, split.getDividerPositions()[0]));
            split.getItems().remove(inspector);
        }
        detailsToggle.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), visible);
    }

    private boolean isInspectorVisible() {
        return split.getItems().contains(inspector);
    }

    private void openSelected() {
        if (inspector.getUserData() instanceof GraphNode node) openAction.accept(node);
    }

    private void showComparison() {
        if (comparison == null || source == null) {
            status.setText("Generate another graph to compare it with the current one.");
            return;
        }
        Set<String> previous = new HashSet<>();
        comparison.nodes().forEach(node -> previous.add(node.id()));
        long added = source.nodes().stream().filter(node -> !previous.contains(node.id())).count();
        Set<String> current = new HashSet<>();
        source.nodes().forEach(node -> current.add(node.id()));
        long removed = comparison.nodes().stream().filter(node -> !current.contains(node.id())).count();
        inspectorTitle.setText("Graph comparison");
        inspectorKind.setText("Current result compared with the previous result");
        inspectorFacts.getChildren().setAll(fact("Added nodes", added), fact("Removed nodes", removed),
                fact("Current edges", source.edges().size()), fact("Previous edges", comparison.edges().size()));
        inspector.setUserData(null);
        openButton.setDisable(true);
        setInspectorVisible(true);
    }

    private void copyJson() {
        if (source == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(new JsonGraphExporter().export(filtered()));
        Clipboard.getSystemClipboard().setContent(content);
        status.setText("Graph JSON copied to the clipboard.");
    }

    private void export(String format) {
        if (source == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export graph");
        String extension = format.equals("mermaid") ? "mmd" : format;
        chooser.setInitialFileName("frost-" + source.type().name().toLowerCase(Locale.ROOT) + "." + extension);
        File file = chooser.showSaveDialog(owner);
        if (file == null) return;
        try {
            if (format.equals("png")) {
                String data = String.valueOf(web.getEngine().executeScript("window.frostPng()"));
                int comma = data.indexOf(',');
                if (comma < 0) throw new IOException("Renderer did not return PNG data");
                Files.write(file.toPath(), Base64.getDecoder().decode(data.substring(comma + 1)));
            } else {
                Files.writeString(file.toPath(), GraphExporters.byFormat(format).export(filtered()), StandardCharsets.UTF_8);
            }
            status.setText("Exported " + file.getName() + ".");
        } catch (Exception exception) {
            status.setText("Export failed: " + exception.getMessage());
        }
    }

    private void runLayout() {
        if (source == null) return;
        execute("window.frostLayout(" + jsString(rendererLayout(layoutMode, source.type())) + ","
                + jsString(flowDirection.rendererValue()) + ")");
    }

    private LayoutMode preferredLayout(Graph graph) {
        return switch (graph.type()) {
            case CONTROL_FLOW, EXCEPTION_FLOW, INHERITANCE, TRANSFORMER_PIPELINE,
                    CONFIGURATION_PREVIEW, OBFUSCATION_PREVIEW, BUILD_EXECUTION, MAPPING -> LayoutMode.DIRECTIONAL;
            case PACKAGE_DEPENDENCY -> LayoutMode.RADIAL;
            default -> graph.nodes().size() > 350 ? LayoutMode.DIRECTIONAL : LayoutMode.FORCE;
        };
    }

    private static String rendererLayout(LayoutMode mode, GraphType type) {
        LayoutMode value = mode == LayoutMode.AUTOMATIC ? switch (type) {
            case CONTROL_FLOW, EXCEPTION_FLOW, INHERITANCE, TRANSFORMER_PIPELINE,
                    CONFIGURATION_PREVIEW, OBFUSCATION_PREVIEW, BUILD_EXECUTION, MAPPING -> LayoutMode.DIRECTIONAL;
            case PACKAGE_DEPENDENCY -> LayoutMode.RADIAL;
            default -> LayoutMode.FORCE;
        } : mode;
        return switch (value) {
            case DIRECTIONAL -> "breadthfirst";
            case FORCE -> "cose";
            case RADIAL -> "concentric";
            case GRID -> "grid";
            case AUTOMATIC -> "cose";
        };
    }

    private void refreshTheme() {
        if (disposed) return;
        if (source == null) {
            Palette palette = palette();
            web.getEngine().loadContent("<!doctype html><html><body style='margin:0;background:"
                    + palette.background() + ";'></body></html>");
        } else render();
    }

    private Palette palette() {
        ThemeDefinition theme = themes.activeTheme();
        return new Palette(color(theme.token("bg"), "#000000"), color(theme.token("surface"), "#080808"),
                color(theme.token("surface-raised"), "#121212"), color(theme.token("border"), "#2A2A2A"),
                color(theme.token("text"), "#E4E4E7"), color(theme.token("text-muted"), "#A1A1AA"),
                color(themes.accentProperty().get(), theme.token("accent")),
                color(theme.token("accent-soft"), theme.token("surface-raised")),
                color(theme.token("info"), "#6FA7DD"), color(theme.token("success"), "#45C99A"),
                color(theme.token("warning"), "#E3A934"), color(theme.token("danger"), "#EF7178"));
    }

    private void execute(String script) {
        if (disposed || source == null) return;
        try { web.getEngine().executeScript(script); } catch (RuntimeException ignored) { }
    }

    @Override public void close() {
        if (disposed) return;
        disposed = true;
        rendererPoll.stop();
        themes.activeThemeProperty().removeListener(themeListener);
        themes.accentProperty().removeListener(accentListener);
        source = null;
        comparison = null;
        inspector.setUserData(null);
        WebEngine engine = web.getEngine();
        engine.getLoadWorker().cancel();
        engine.load("about:blank");
        split.getItems().clear();
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = GraphViewer.class.getResourceAsStream(path)) {
            if (input == null) throw new FileNotFoundException("Bundled Cytoscape runtime is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String html(String runtime, String graph, Palette colors, LayoutMode preferred,
                               FlowDirection direction) {
        String initialLayout = rendererLayout(preferred, GraphType.CUSTOM);
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; connect-src 'none'; font-src 'none'\">"
                + "<style>html,body,#graph{margin:0;width:100%;height:100%;overflow:hidden;background:" + colors.background()
                + ";font-family:Inter,'Segoe UI',sans-serif}#graph:focus{outline:2px solid " + colors.accent()
                + ";outline-offset:-2px}</style></head><body><div id='graph' tabindex='0' aria-label='Interactive graph canvas'></div><script>"
                + runtime + "</script><script>try{"
                + "const raw=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob('" + graph + "'),c=>c.charCodeAt(0))));"
                + "const elements=[...raw.nodes.map(n=>({data:{id:n.id,label:n.label,type:n.type,metadata:n.metadata?.values||{},library:n.type==='LIBRARY_CLASS'||n.metadata?.values?.library===true,focus:n.metadata?.values?.focus===true}})),"
                + "...raw.edges.map(e=>({data:{id:e.id,source:e.source,target:e.target,type:e.type,label:e.label||'',metadata:e.metadata?.values||{},flow:e.metadata?.values?.flow||''}}))];"
                + "window.frostSelected='';window.frostError='';"
                + "const cy=window.frostCy=cytoscape({container:document.getElementById('graph'),elements,pixelRatio:'auto',minZoom:.05,maxZoom:5,wheelSensitivity:.22,hideEdgesOnViewport:elements.length>1200,textureOnViewport:elements.length>1800,"
                + "style:[{selector:'node',style:{'background-color':'" + colors.raised() + "','border-color':'" + colors.border()
                + "','border-width':1.5,'color':'" + colors.text() + "','font-size':11,'font-family':'Inter, Segoe UI, sans-serif','label':'data(label)','text-wrap':'ellipsis','text-max-width':150,'text-valign':'center','text-halign':'center','min-zoomed-font-size':8,'width':72,'height':38,'shape':'round-rectangle'}},"
                + "{selector:'node[type=\"CLASS\"]',style:{'background-color':'" + colors.accentSoft() + "','border-color':'" + colors.accent() + "'}},"
                + "{selector:'node[type=\"LIBRARY_CLASS\"]',style:{'background-color':'" + colors.surface() + "','border-color':'" + colors.muted() + "','color':'" + colors.muted() + "'}},"
                + "{selector:'node[type=\"METHOD\"]',style:{'background-color':'" + colors.surface() + "','border-color':'" + colors.info() + "','width':96}},"
                + "{selector:'node[focus]',style:{'background-color':'" + colors.accentSoft() + "','border-color':'" + colors.accent() + "','border-width':2.5}},"
                + "{selector:'node[type=\"TRANSFORMER\"]',style:{'background-color':'" + colors.raised() + "','border-color':'" + colors.success() + "','shape':'hexagon','width':94,'height':48}},"
                + "{selector:'node[type=\"PACKAGE\"]',style:{'background-color':'" + colors.raised() + "','border-color':'" + colors.accent() + "','shape':'round-rectangle','width':110,'height':44}},"
                + "{selector:'node[type*=\"BLOCK\"],node[type=\"EXCEPTION_HANDLER\"]',style:{'border-color':'" + colors.info() + "','width':88,'height':42}},"
                + "{selector:'node[type=\"WARNING\"],node[type=\"UNREACHABLE_BLOCK\"]',style:{'border-color':'" + colors.warning() + "','background-color':'" + colors.surface() + "'}},"
                + "{selector:'edge',style:{'width':1.35,'line-color':'" + colors.muted() + "','target-arrow-color':'" + colors.muted() + "','target-arrow-shape':'triangle','curve-style':'bezier','arrow-scale':.75,'label':'data(label)','font-size':9,'color':'" + colors.muted() + "','text-background-color':'" + colors.background() + "','text-background-opacity':.85,'text-background-padding':2,'min-zoomed-font-size':9}},"
                + "{selector:'edge[type=\"CONFLICTS\"],edge[type=\"EXCEPTION\"]',style:{'line-style':'dashed','line-color':'" + colors.danger() + "','target-arrow-color':'" + colors.danger() + "'}},"
                + "{selector:'edge[type=\"CONDITIONAL_TRUE\"]',style:{'line-color':'" + colors.success() + "','target-arrow-color':'" + colors.success() + "'}},"
                + "{selector:'edge[type=\"CONDITIONAL_FALSE\"]',style:{'line-color':'" + colors.warning() + "','target-arrow-color':'" + colors.warning() + "'}},"
                + "{selector:'edge[flow=\"incoming\"]',style:{'line-color':'" + colors.success() + "','target-arrow-color':'" + colors.success() + "','width':2}},"
                + "{selector:'edge[flow=\"outgoing\"]',style:{'line-color':'" + colors.accent() + "','target-arrow-color':'" + colors.accent() + "','width':2}},"
                + "{selector:'.neighbor',style:{'opacity':1}}, {selector:'node:selected',style:{'border-width':3,'border-color':'" + colors.accent() + "','overlay-opacity':0}},"
                + "{selector:'.search-hit',style:{'border-width':4,'border-color':'" + colors.warning() + "','z-index':20}}],layout:{name:'preset'}});"
                + "window.frostLayout=(name,direction)=>{const opts=name==='breadthfirst'?{name,directed:true,direction:direction||'rightward',spacingFactor:1.2,padding:54,animate:false}:name==='cose'?{name,idealEdgeLength:110,nodeRepulsion:420000,gravity:.25,numIter:700,padding:48,animate:false}:name==='concentric'?{name,minNodeSpacing:42,levelWidth:()=>1,padding:48,animate:false}:{name,padding:48,animate:false};cy.layout(opts).run();setTimeout(()=>cy.fit(undefined,48),30)};"
                + "window.frostFit=()=>cy.fit(undefined,48);window.frostZoom=f=>{cy.zoom({level:Math.max(.05,Math.min(5,cy.zoom()*f)),renderedPosition:{x:cy.width()/2,y:cy.height()/2}})};"
                + "window.frostSearch=q=>{cy.nodes().removeClass('search-hit');if(!q)return;const s=q.toLowerCase();cy.nodes().filter(n=>(n.data('label')+' '+n.id()+' '+JSON.stringify(n.data('metadata'))).toLowerCase().includes(s)).addClass('search-hit')};"
                + "window.frostPng=()=>cy.png({full:true,scale:2,bg:'" + colors.background() + "'});"
                + "cy.on('tap','node',e=>{cy.elements().removeClass('neighbor');const n=e.target;n.closedNeighborhood().addClass('neighbor');window.frostSelected=n.id()});"
                + "cy.on('tap',e=>{if(e.target===cy){cy.elements().removeClass('neighbor');window.frostSelected=''}});"
                + "cy.on('dbltap','node',e=>cy.animate({fit:{eles:e.target.closedNeighborhood(),padding:80},duration:180}));"
                + "window.frostLayout('" + initialLayout + "','" + direction.rendererValue() + "');"
                + "}catch(e){window.frostError=(e&&e.message)||String(e);document.body.textContent='Renderer error: '+window.frostError}</script></body></html>";
    }

    private static String jsString(String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", " ").replace("\n", " ");
        return "'" + safe + "'";
    }

    private static String pretty(String value) {
        String lower = value.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? "" : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String formatValue(Object value) {
        if (value instanceof java.util.Collection<?> collection)
            return collection.isEmpty() ? "None" : collection.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining("\n"));
        if (value instanceof java.util.Map<?, ?> map)
            return map.isEmpty() ? "None" : map.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));
        return String.valueOf(value);
    }

    private static String color(String value, String fallback) {
        return value != null && COLOR.matcher(value).matches() ? value : fallback;
    }

    private record Palette(String background, String surface, String raised, String border, String text,
                           String muted, String accent, String accentSoft, String info, String success,
                           String warning, String danger) { }
}
