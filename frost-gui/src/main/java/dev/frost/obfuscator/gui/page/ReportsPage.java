package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.analysis.*;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.StatusChip;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.transformer.TransformerConfig;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class ReportsPage implements PageView {
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_6);
    private final ScrollPane root = Ui.pageScroll(content);
    private final FlowPane inventorySummary = new FlowPane(Ui.SPACE_3, Ui.SPACE_3);
    private final VBox recommendations = new VBox(Ui.SPACE_3);
    private final VBox compatibility = new VBox(Ui.SPACE_3);
    private final VBox postBuild = new VBox(Ui.SPACE_4);
    private final TableView<BytecodeInventory.ClassInsight> classTable = table();
    private final TableView<BytecodeInventory.MethodInsight> methodTable = table();
    private final TableView<BytecodeInventory.StringInsight> stringTable = table();
    private final TableView<BytecodeInventory.ResourceEntry> resourceTable = table();
    private final TextField classSearch = search("Filter classes or packages");
    private final TextField methodSearch = search("Filter owners, methods, descriptors, or flags");
    private final TextField stringSearch = search("Filter literal values, categories, or locations");
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(180));
    private boolean active;
    private boolean refreshPending;

    public ReportsPage(AppContext context) {
        this.context = context;
        SmoothScroll.install(root, context.themeManager());
        content.getStyleClass().addAll("page", "reports-page", "analytics-page");
        content.setPadding(Ui.pageInsets());

        Button apply = Ui.button("Apply recommended setup", "primary-button", () -> {
            int count = context.recommendationEngine().applyRecommendedSetup(context.projectState());
            context.validationCoordinator().validateNow();
            refresh();
            context.notifications().show(count == 0
                    ? "Recommended setup is already applied"
                    : "Applied " + count + " analysis recommendation" + (count == 1 ? "" : "s"));
        });
        HBox heading = new HBox(Ui.SPACE_4,
                Ui.pageHeader("Project Analytics",
                        "Bytecode-level inventory, compatibility evidence, transformer coverage, and post-build impact."),
                Ui.spacer(), apply);
        heading.setAlignment(Pos.TOP_LEFT);

        configureTables();
        TabPane details = new TabPane(
                tab("Classes", inventoryTab(classSearch, classTable)),
                tab("Methods", inventoryTab(methodSearch, methodTable)),
                tab("Strings", inventoryTab(stringSearch, stringTable)),
                tab("Resources", resourceTable)
        );
        details.getStyleClass().add("analytics-tabs");
        details.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        GridPane intelligence = new GridPane();
        intelligence.setHgap(Ui.SPACE_6);
        intelligence.setVgap(Ui.SPACE_6);
        intelligence.getColumnConstraints().addAll(percent(54), percent(46));
        intelligence.add(Ui.section("Recommended transformer setup",
                "Every action updates the real transformer configuration and re-validates compatibility.",
                recommendations), 0, 0);
        intelligence.add(Ui.section("Compatibility & conflicts",
                "Evidence from call sites, metadata, archive layout, and the selected pass combination.",
                compatibility), 1, 0);

        content.getChildren().addAll(
                heading,
                Ui.section("Archive inventory",
                        "Counts come from parsed JVM structures—not byte-pattern estimates.", inventorySummary),
                intelligence,
                Ui.section("Complete bytecode inventory",
                        "Search every class, method, string literal, and resource discovered in the archive.", details),
                Ui.section("Post-build effectiveness",
                        "Exact transformer counters and structural before/after comparison appear after a successful build.",
                        postBuild),
                reportSettings()
        );

        searchDelay.setOnFinished(event -> {
            refreshClassTable();
            refreshMethodTable();
            refreshStringTable();
        });
        classSearch.textProperty().addListener((obs, old, value) -> searchDelay.playFromStart());
        methodSearch.textProperty().addListener((obs, old, value) -> searchDelay.playFromStart());
        stringSearch.textProperty().addListener((obs, old, value) -> searchDelay.playFromStart());
        context.projectState().analysisProperty().addListener((obs, old, value) -> requestRefresh());
        context.projectState().buildAnalyticsProperty().addListener((obs, old, value) -> requestRefresh());
        context.projectState().revisionProperty().addListener((obs, old, value) -> requestRefresh());
        refresh();
    }

    @SuppressWarnings("unchecked")
    private void configureTables() {
        classTable.getColumns().addAll(
                column("Class", 330, BytecodeInventory.ClassInsight::name),
                column("Kind", 110, BytecodeInventory.ClassInsight::kind),
                numberColumn("Fields", 75, item -> item.fields()),
                numberColumn("Methods", 80, item -> item.methods()),
                numberColumn("Instructions", 105, item -> item.instructions()),
                numberColumn("Strings", 75, item -> item.strings()),
                column("Flags", 150, BytecodeInventory.ClassInsight::flags)
        );
        methodTable.getColumns().addAll(
                column("Owner", 290, BytecodeInventory.MethodInsight::owner),
                column("Method", 175, BytecodeInventory.MethodInsight::name),
                column("Descriptor", 260, BytecodeInventory.MethodInsight::descriptor),
                numberColumn("Instructions", 100, item -> item.instructions()),
                numberColumn("Complexity", 90, item -> item.complexity()),
                numberColumn("Strings", 70, item -> item.strings()),
                numberColumn("Calls", 65, item -> item.calls()),
                column("Flags", 180, BytecodeInventory.MethodInsight::flags)
        );
        stringTable.getColumns().addAll(
                column("Value", 430, item -> displayLiteral(item.value())),
                column("Category", 150, BytecodeInventory.StringInsight::category),
                numberColumn("Occurrences", 105, item -> item.occurrences()),
                numberColumn("Characters", 90, item -> item.characters()),
                column("Locations", 470, item -> String.join(", ", item.locations()))
        );
        resourceTable.getColumns().addAll(
                column("Resource path", 620, BytecodeInventory.ResourceEntry::name),
                column("Type", 180, BytecodeInventory.ResourceEntry::type),
                column("Bytes", 160, item -> formatBytes(item.bytes()))
        );
    }

    private Node reportSettings() {
        TransformerConfig report = context.projectState().configuration().getTransformers()
                .computeIfAbsent("statistics-report", key -> new TransformerConfig());
        CheckBox enabled = new CheckBox("Write a machine-readable report after successful builds");
        enabled.setSelected(report.isEnabled());
        CustomComboBox<String> format = new CustomComboBox<>(List.of("json", "html"));
        format.setValue(report.getOption("format", "json"));
        TextField output = new TextField(report.getOption("output", "frost-report.json"));
        output.getStyleClass().add("text-input");
        enabled.selectedProperty().addListener((obs, old, value) -> {
            report.setEnabled(value);
            context.projectState().touch();
        });
        format.valueProperty().addListener((obs, old, value) -> {
            report.getOptions().put("format", value);
            context.projectState().touch();
        });
        output.textProperty().addListener((obs, old, value) -> {
            report.getOptions().put("output", value.trim());
            context.projectState().touch();
        });
        return Ui.section("Report export", "Optional JSON or HTML counters for automation and archival.",
                enabled, Ui.fieldRow("Format", format), Ui.fieldRow("Report path", output));
    }

    private void refresh() {
        refreshPending = false;
        ProjectAnalysis analysis = context.projectState().analysis();
        refreshSummary(analysis);
        refreshRecommendations(analysis);
        refreshCompatibility(analysis);
        refreshClassTable();
        refreshMethodTable();
        refreshStringTable();
        refreshResources(analysis);
        refreshPostBuild();
    }

    private void refreshSummary(ProjectAnalysis analysis) {
        inventorySummary.getChildren().clear();
        if (!analysis.analyzed()) {
            inventorySummary.getChildren().add(Ui.label(
                    "Select and analyze an input JAR to populate the bytecode inventory.", "empty-state-copy"));
            return;
        }
        BytecodeInventory data = analysis.inventory();
        inventorySummary.getChildren().addAll(
                metric("Classes", analysis.classCount(), data.interfaceCount() + " interfaces · "
                        + data.recordCount() + " records"),
                metric("Methods", data.methodCount(), data.virtualizableMethodCount() + " virtualization candidates"),
                metric("Fields", data.fieldCount(), data.annotationCount() + " annotations"),
                metric("Instructions", data.instructionCount(), data.branchCount() + " branches · "
                        + data.tryCatchCount() + " handlers"),
                metric("Strings", data.stringLiteralCount(), data.uniqueStringCount() + " unique · "
                        + data.stringCharacterCount() + " characters"),
                metric("Resources", analysis.resourceCount(), formatBytes(data.resources().values().stream()
                        .mapToLong(BytecodeInventory.ResourceInsight::bytes).sum()))
        );
    }

    private void refreshRecommendations(ProjectAnalysis analysis) {
        recommendations.getChildren().clear();
        List<Recommendation> items = context.recommendationEngine().recommend(analysis,
                context.projectState().configuration(), context.projectState().profileProperty().get(),
                context.projectState().outputSizeLimitMbProperty().get(),
                context.projectState().runtimeOverheadPreferenceProperty().get());
        if (items.isEmpty()) {
            recommendations.getChildren().addAll(new StatusChip("Configured", "success"),
                    Ui.label("The selected setup matches the current analysis.", "empty-state-copy"));
            return;
        }
        items.stream().limit(12).forEach(item -> recommendations.getChildren().add(recommendationRow(item)));
    }

    private Node recommendationRow(Recommendation recommendation) {
        StatusChip category = new StatusChip(recommendation.category(), "info");
        Label title = Ui.label(recommendation.title(), "problem-title");
        Label explanation = Ui.label(recommendation.explanation(), "problem-copy");
        title.setWrapText(true);
        explanation.setWrapText(true);
        VBox copy = new VBox(Ui.SPACE_1, title, explanation);
        copy.setMinWidth(0);
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox row = new HBox(Ui.SPACE_3, category, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("analytics-action-row");
        if (recommendation.actionable()) {
            Button apply = Ui.button(recommendation.quickFix(), "secondary-button", () -> {
                recommendation.action().accept(context.projectState());
                context.validationCoordinator().validateNow();
                refresh();
                context.notifications().show("Applied: " + recommendation.title());
            });
            row.getChildren().add(apply);
        }
        return row;
    }

    private void refreshCompatibility(ProjectAnalysis analysis) {
        compatibility.getChildren().clear();
        if (!analysis.analyzed()) {
            compatibility.getChildren().add(Ui.label(
                    "Compatibility evidence appears after analysis.", "empty-state-copy"));
            return;
        }
        List<String> activeConflicts = context.recommendationEngine()
                .incompatibilities(analysis, context.projectState().configuration());
        activeConflicts.forEach(issue -> compatibility.getChildren().add(
                signalRow("Active conflict", "error", issue, "")));
        analysis.inventory().compatibilitySignals().forEach(signal -> compatibility.getChildren().add(
                signalRow(signal.title(), signal.severity(), signal.evidence(),
                        signal.affectedTransformers().isEmpty() ? ""
                                : "Affected: " + String.join(", ", signal.affectedTransformers()))));
        if (compatibility.getChildren().isEmpty()) {
            compatibility.getChildren().addAll(new StatusChip("No conflicts detected", "success"),
                    Ui.label("No evidence-backed incompatibilities were found for this archive and setup.",
                            "empty-state-copy"));
        }
    }

    private static Node signalRow(String title, String severity, String evidence, String affected) {
        String tone = severity.equalsIgnoreCase("error") ? "error"
                : severity.equalsIgnoreCase("warning") ? "warning" : "info";
        Label heading = Ui.label(title, "problem-title");
        Label detail = Ui.label(evidence, "problem-copy");
        heading.setWrapText(true);
        detail.setWrapText(true);
        VBox copy = new VBox(Ui.SPACE_1, heading, detail);
        if (!affected.isBlank()) {
            Label transformers = Ui.label(affected, "muted-text");
            transformers.setWrapText(true);
            copy.getChildren().add(transformers);
        }
        HBox row = new HBox(Ui.SPACE_3, new StatusChip(titleCase(severity), tone), copy);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("analytics-signal-row");
        return row;
    }

    private void refreshClassTable() {
        ProjectAnalysis analysis = context.projectState().analysis();
        if (!analysis.analyzed()) {
            classTable.setItems(FXCollections.observableArrayList());
            return;
        }
        String query = normalized(classSearch.getText());
        classTable.setItems(FXCollections.observableArrayList(analysis.inventory().classes().stream()
                .filter(item -> query.isBlank() || contains(item.name(), query)
                        || contains(item.packageName(), query) || contains(item.kind(), query)
                        || contains(item.flags(), query)).toList()));
    }

    private void refreshMethodTable() {
        ProjectAnalysis analysis = context.projectState().analysis();
        if (!analysis.analyzed()) {
            methodTable.setItems(FXCollections.observableArrayList());
            return;
        }
        String query = normalized(methodSearch.getText());
        methodTable.setItems(FXCollections.observableArrayList(analysis.inventory().methods().stream()
                .filter(item -> query.isBlank() || contains(item.owner(), query)
                        || contains(item.name(), query) || contains(item.descriptor(), query)
                        || contains(item.flags(), query)).toList()));
    }

    private void refreshStringTable() {
        ProjectAnalysis analysis = context.projectState().analysis();
        if (!analysis.analyzed()) {
            stringTable.setItems(FXCollections.observableArrayList());
            return;
        }
        String query = normalized(stringSearch.getText());
        stringTable.setItems(FXCollections.observableArrayList(analysis.inventory().strings().stream()
                .filter(item -> query.isBlank() || contains(item.value(), query)
                        || contains(item.category(), query)
                        || item.locations().stream().anyMatch(location -> contains(location, query))).toList()));
    }

    private void refreshResources(ProjectAnalysis analysis) {
        if (!analysis.analyzed()) {
            resourceTable.setItems(FXCollections.observableArrayList());
            return;
        }
        resourceTable.setItems(FXCollections.observableArrayList(analysis.inventory().resourceEntries()));
    }

    private void refreshPostBuild() {
        postBuild.getChildren().clear();
        BuildAnalytics analytics = context.projectState().buildAnalytics();
        if (!analytics.available()) {
            postBuild.getChildren().add(Ui.label(
                    "Run a protected build to compare exact input/output structures and transformer counters.",
                    "empty-state-copy"));
            return;
        }
        FlowPane metrics = new FlowPane(Ui.SPACE_3, Ui.SPACE_3);
        analytics.metrics().forEach(item -> metrics.getChildren().add(
                metric(item.label(), item.value(), item.detail())));
        VBox optimizationList = new VBox(Ui.SPACE_2);
        analytics.optimizations().forEach(item -> optimizationList.getChildren().add(
                signalRow("Optimization", "info", item, "")));
        postBuild.getChildren().addAll(metrics,
                Ui.section("Optimization suggestions",
                        "Recommendations use measured coverage and growth from this build.", optimizationList));
    }

    private static Node metric(String label, long value, String detail) {
        return metric(label, String.format("%,d", value), detail);
    }

    private static Node metric(String label, String value, String detail) {
        Label number = Ui.label(value, "analytics-metric-value");
        Label name = Ui.label(label, "analytics-metric-label");
        Label copy = Ui.label(detail, "analytics-metric-detail");
        copy.setWrapText(true);
        VBox metric = new VBox(Ui.SPACE_1, number, name, copy);
        metric.getStyleClass().add("analytics-metric");
        return metric;
    }

    private static Node inventoryTab(TextField search, TableView<?> table) {
        VBox box = new VBox(Ui.SPACE_3, search, table);
        box.setPadding(new javafx.geometry.Insets(Ui.SPACE_4, 0, 0, 0));
        return box;
    }

    private static <T> TableView<T> table() {
        TableView<T> table = new TableView<>();
        table.getStyleClass().add("analytics-table");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(520);
        table.setPlaceholder(Ui.label("No matching inventory entries.", "empty-state-copy"));
        return table;
    }

    private static TextField search(String prompt) {
        TextField search = new TextField();
        search.setPromptText(prompt);
        search.getStyleClass().addAll("text-input", "analytics-search");
        return search;
    }

    private static <T> TableColumn<T, String> column(String title, double width,
                                                     Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                value.apply(cell.getValue()) == null ? "" : value.apply(cell.getValue())));
        return column;
    }

    private static <T> TableColumn<T, String> numberColumn(String title, double width,
                                                           Function<T, Number> value) {
        return column(title, width, item -> String.format("%,d", value.apply(item).longValue()));
    }

    private static Tab tab(String title, Node content) {
        return new Tab(title, content);
    }

    private static ColumnConstraints percent(double value) {
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(value);
        column.setHgrow(Priority.ALWAYS);
        return column;
    }

    private static String displayLiteral(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024d;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024d);
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) return "Info";
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void requestRefresh() {
        if (active) refresh();
        else refreshPending = true;
    }

    @Override
    public Node root() {
        return root;
    }

    @Override
    public void onShown() {
        active = true;
        if (refreshPending) refresh();
    }

    @Override
    public void onHidden() {
        active = false;
    }
}
