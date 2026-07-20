package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.analysis.ProjectAnalysis;
import dev.frost.obfuscator.gui.analysis.RecommendationEngine;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.build.BuildRecord;
import dev.frost.obfuscator.gui.component.StatusChip;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.gui.protection.ProtectionProfiles;
import dev.frost.obfuscator.gui.validation.Problem;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public final class OverviewPage implements PageView {
    private final AppContext context;
    private final Consumer<PageId> navigate;
    private final VBox content = new VBox(Ui.SPACE_6);
    private final ScrollPane root = Ui.pageScroll(content);
    private final VBox projectInfo = new VBox(Ui.SPACE_3);
    private final VBox readiness = new VBox(Ui.SPACE_3);
    private final VBox problems = new VBox(Ui.SPACE_2);
    private final VBox impact = new VBox(Ui.SPACE_3);
    private final VBox profile = new VBox(Ui.SPACE_3);
    private final HBox facts = new HBox(Ui.SPACE_6);
    private final HBox buildTimeline = new HBox(Ui.SPACE_6);
    private final Label readinessTitle = Ui.label("Choose an input JAR to begin", "readiness-title");
    private boolean active;
    private boolean refreshPending;

    public OverviewPage(AppContext context, Consumer<PageId> navigate) {
        this.context = context;
        this.navigate = navigate;
        SmoothScroll.install(root, context.themeManager());
        content.getStyleClass().addAll("page", "overview-page");
        content.setPadding(Ui.pageInsets());
        content.setMinWidth(0);

        VBox heading = Ui.pageHeader("Overview", "A high-level view of project detection, protection readiness, and build impact.");
        Node readinessBand = readinessBand();

        GridPane columns = new GridPane();
        columns.setHgap(Ui.SPACE_6);
        columns.setVgap(Ui.SPACE_6);
        VBox primary = new VBox(Ui.SPACE_6,
                Ui.section("Project information", "Detected from the selected archive.", projectInfo),
                Ui.section("Problems preview", "Resolve important items before building.", problems));
        VBox secondary = new VBox(Ui.SPACE_6,
                Ui.section("Selected protection profile", "Centralized defaults remain fully editable.", profile),
                Ui.section("Build impact", "Estimated from this project and enabled transformers.", impact));
        primary.getStyleClass().add("overview-primary");
        secondary.getStyleClass().add("overview-secondary");
        primary.setMinWidth(0);
        secondary.setMinWidth(0);
        primary.setMaxWidth(Double.MAX_VALUE);
        secondary.setMaxWidth(Double.MAX_VALUE);
        configureColumns(columns, primary, secondary, false);

        VBox recent = Ui.section("Recent builds", "Timing and outcomes from this session.", buildTimeline());
        content.getChildren().addAll(heading, readinessBand, columns, recent);

        root.viewportBoundsProperty().addListener((obs, old, bounds) -> {
            boolean narrow = bounds.getWidth() < 1040;
            configureColumns(columns, primary, secondary, narrow);
        });
        context.projectState().analysisProperty().addListener((obs, old, value) -> requestRefresh());
        context.projectState().problems().addListener((javafx.collections.ListChangeListener<Problem>) change -> requestRefresh());
        context.projectState().profileProperty().addListener((obs, old, value) -> requestRefresh());
        context.projectState().revisionProperty().addListener((obs, old, value) -> requestRefresh());
        context.projectState().buildHistory().addListener(
                (javafx.collections.ListChangeListener<? super BuildRecord>) change -> requestRefresh());
        refresh();
    }

    private Node readinessBand() {
        VBox copy = new VBox(Ui.SPACE_2, readinessTitle, facts);
        copy.setMinWidth(0);
        facts.setMinWidth(0);
        HBox.setHgrow(copy, Priority.ALWAYS);
        Button validate = Ui.button("Validate project", "secondary-button", () -> {
            context.buildController().validate();
            navigate.accept(PageId.VALIDATION);
        });
        Button build = Ui.button("Build protected JAR", "primary-button", () -> {
            context.buildController().build();
            navigate.accept(PageId.BUILD);
        });
        HBox actions = new HBox(Ui.SPACE_3, validate, build);
        actions.setAlignment(Pos.TOP_RIGHT);
        HBox band = new HBox(Ui.SPACE_6, copy, actions);
        band.setAlignment(Pos.CENTER_LEFT);
        band.getStyleClass().add("readiness-band");
        return band;
    }

    private Node buildTimeline() {
        buildTimeline.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(buildTimeline, Priority.NEVER);
        return buildTimeline;
    }

    private void refresh() {
        refreshPending = false;
        ProjectAnalysis analysis = context.projectState().analysis();
        long errors = context.projectState().problems().stream().filter(p -> p.severity() == Problem.Severity.ERROR).count();
        long warnings = context.projectState().problems().stream().filter(p -> p.severity() == Problem.Severity.WARNING).count();
        long recs = context.projectState().problems().stream().filter(p -> p.severity() == Problem.Severity.RECOMMENDATION).count();
        readinessTitle.setText(!analysis.analyzed() ? "Choose an input JAR to begin"
                : errors > 0 ? "Needs " + errors + " required fix" + (errors == 1 ? "" : "es")
                : warnings > 0 ? "Ready with " + warnings + " warning" + (warnings == 1 ? "" : "s")
                : "Ready with " + recs + " recommendation" + (recs == 1 ? "" : "s"));

        facts.getChildren().clear();
        if (analysis.analyzed()) {
            facts.getChildren().addAll(
                    fact("Input", analysis.jar().getFileName().toString()),
                    fact("Runtime", analysis.javaVersion() == 0 ? "Unknown Java" : "Java " + analysis.javaVersion()),
                    fact("Classes", String.format("%,d", analysis.classCount())),
                    fact("Main class", analysis.mainClass().isBlank() ? "Not declared" : "Detected"),
                    fact("Profile", context.projectState().profileProperty().get())
            );
        } else {
            Label description = Ui.label(
                    "Automatic analysis will detect runtime, entrypoints, frameworks, and dependencies.",
                    "section-description");
            description.setWrapText(true);
            description.setMinWidth(0);
            facts.getChildren().add(description);
        }

        projectInfo.getChildren().clear();
        if (!analysis.analyzed()) {
            Button choose = Ui.button("Choose input JAR", "primary-button", () -> navigate.accept(PageId.INPUT));
            projectInfo.getChildren().addAll(Ui.label("No project analyzed yet.", "empty-state-title"),
                    Ui.label("Select an archive and Frostfuscator will recommend safe defaults.", "empty-state-copy"), choose);
        } else {
            projectInfo.getChildren().addAll(
                    info("Project", stripExtension(analysis.jar().getFileName().toString())),
                    info("Location", analysis.jar().getParent().toString()),
                    info("Input file", analysis.jar().getFileName().toString()),
                    info("Main class", analysis.mainClass().isBlank() ? "Not declared" : analysis.mainClass()),
                    info("Dependencies", analysis.resolvedLibraries().size() + " resolved · "
                            + analysis.unresolvedLibraries().size() + " unresolved"),
                    info("Frameworks", analysis.frameworks().isEmpty() ? "None detected" : String.join(", ", analysis.frameworks()))
            );
        }

        readiness.getChildren().clear();
        problems.getChildren().clear();
        if (context.projectState().problems().isEmpty()) {
            problems.getChildren().add(Ui.label("No problems detected. Validate again after changing protection settings.",
                    "empty-state-copy"));
        } else {
            context.projectState().problems().stream().limit(3).forEach(problem -> problems.getChildren().add(problemRow(problem)));
            if (context.projectState().problems().size() > 3) {
                problems.getChildren().add(Ui.button("View all problems (" + context.projectState().problems().size() + ")",
                        "inline-button", () -> navigate.accept(PageId.VALIDATION)));
            }
        }

        profile.getChildren().clear();
        ProtectionProfiles.Definition selected = ProtectionProfiles.definitions().stream()
                .filter(item -> item.name().equalsIgnoreCase(context.projectState().profileProperty().get()))
                .findFirst().orElse(ProtectionProfiles.definitions().getLast());
        profile.getChildren().addAll(new StatusChip(selected.name(), "info"),
                Ui.label(selected.description(), "section-description"),
                info("Compatibility", selected.compatibility()),
                info("Protection", selected.strength()),
                Ui.button("Configure profile", "secondary-button", () -> navigate.accept(PageId.PROTECTION)));

        impact.getChildren().clear();
        RecommendationEngine.Impact estimate = context.recommendationEngine()
                .estimate(analysis, context.projectState().configuration());
        impact.getChildren().addAll(
                info("Output size increase", "+" + estimate.outputGrowthPercent() + "%"),
                info("Runtime overhead", "~" + estimate.runtimeOverheadPercent() + "%"),
                info("Build time", "~" + estimate.buildSeconds() + " seconds")
        );
        refreshBuilds();
    }

    private void requestRefresh() {
        if (active) refresh();
        else refreshPending = true;
    }

    private void refreshBuilds() {
        buildTimeline.getChildren().clear();
        if (context.projectState().buildHistory().isEmpty()) {
            buildTimeline.getChildren().add(Ui.label("No builds yet. Your first completed build will appear here.", "empty-state-copy"));
            return;
        }
        context.projectState().buildHistory().stream().limit(4).forEach(record -> {
            VBox item = new VBox(Ui.SPACE_1,
                    new StatusChip(record.status().name(), record.status() == dev.frost.obfuscator.gui.build.BuildRecord.Status.SUCCESS
                            ? "success" : record.status() == dev.frost.obfuscator.gui.build.BuildRecord.Status.FAILED ? "error" : "warning"),
                    Ui.label(record.time().format(DateTimeFormatter.ofPattern("HH:mm")) + " · "
                            + record.duration().toSeconds() + "s", "muted-text"));
            HBox.setHgrow(item, Priority.ALWAYS);
            buildTimeline.getChildren().add(item);
        });
    }

    private Node problemRow(Problem problem) {
        String tone = switch (problem.severity()) {
            case ERROR -> "error";
            case WARNING -> "warning";
            default -> "info";
        };
        StatusChip chip = new StatusChip(titleCase(problem.severity().name()), tone);
        Label title = Ui.label(problem.title(), "problem-title");
        Label explanation = Ui.label(problem.explanation(), "problem-copy");
        title.setWrapText(true);
        explanation.setWrapText(true);
        title.setMinWidth(0);
        explanation.setMinWidth(0);
        VBox copy = new VBox(Ui.SPACE_1, title, explanation);
        copy.setMinWidth(0);
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox row = new HBox(Ui.SPACE_3, chip, copy);
        row.setMinWidth(0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("problem-row", "problem-" + tone);
        if (problem.hasQuickFix()) {
            Button fix = Ui.button(problem.quickFixLabel(), "inline-button", () -> {
                problem.quickFix().accept(context.projectState());
                context.validationCoordinator().validateNow();
                context.notifications().show("Applied: " + problem.quickFixLabel());
            });
            fix.setMinWidth(Region.USE_PREF_SIZE);
            fix.setMaxWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(fix);
        }
        return row;
    }

    private static Node fact(String label, String value) {
        VBox fact = new VBox(Ui.SPACE_1, Ui.label(value, "fact-value"), Ui.label(label, "fact-label"));
        return fact;
    }

    private static Node info(String label, String value) {
        Label key = Ui.label(label, "info-key");
        Label data = Ui.label(value, "info-value");
        data.setWrapText(true);
        HBox.setHgrow(data, Priority.ALWAYS);
        HBox row = new HBox(Ui.SPACE_4, key, data);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static void configureColumns(GridPane grid, VBox primary, VBox secondary, boolean stacked) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        if (stacked) {
            ColumnConstraints full = new ColumnConstraints();
            full.setPercentWidth(100);
            full.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(full);
            grid.add(primary, 0, 0);
            grid.add(secondary, 0, 1);
        } else {
            ColumnConstraints main = new ColumnConstraints();
            main.setPercentWidth(68);
            main.setHgrow(Priority.ALWAYS);
            ColumnConstraints aside = new ColumnConstraints();
            aside.setPercentWidth(32);
            aside.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(main, aside);
            grid.add(primary, 0, 0);
            grid.add(secondary, 1, 0);
        }
        GridPane.setHgrow(primary, Priority.ALWAYS);
        GridPane.setHgrow(secondary, Priority.ALWAYS);
    }

    private static String titleCase(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    @Override
    public Node root() { return root; }

    @Override
    public void onShown() {
        active = true;
        root.setVvalue(0);
        if (refreshPending) refresh();
    }

    @Override
    public void onHidden() {
        active = false;
    }
}
