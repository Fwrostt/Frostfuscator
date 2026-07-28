package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.analysis.RecommendationEngine;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.build.BuildRecord;
import dev.frost.obfuscator.gui.component.StatusChip;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.awt.Desktop;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;

public final class BuildPage implements PageView {
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = Ui.label("Ready", "build-status");
    private final Button build;
    private final Button cancel;
    private final Button reveal;
    private final VBox history = new VBox(Ui.SPACE_2);

    public BuildPage(AppContext context) {
        this.context = context;
        SmoothScroll.install(root, context.themeManager());
        content.getStyleClass().addAll("page", "build-page");
        content.setPadding(Ui.pageInsets());

        TextField output = new TextField(context.projectState().configuration().getOutput());
        output.getStyleClass().add("text-input");
        output.setMinWidth(0);
        Button browse = Ui.button("Choose output", "secondary-button", () ->
                context.dialogs().saveJar("protected.jar").ifPresent(path -> output.setText(path.toString())));
        HBox outputRow = new HBox(Ui.SPACE_3, output, browse);
        outputRow.setMinWidth(0);
        HBox.setHgrow(output, Priority.ALWAYS);
        output.textProperty().addListener((obs, old, value) -> {
            context.projectState().configuration().setOutput(value.trim());
            context.projectState().touch();
        });

        RecommendationEngine.Impact estimate = context.recommendationEngine().estimate(
                context.projectState().analysis(), context.projectState().configuration());
        FlowPane impact = new FlowPane(Ui.SPACE_8, Ui.SPACE_3,
                metric("Estimated growth", "+" + estimate.outputGrowthPercent() + "%"),
                metric("Runtime overhead", "~" + estimate.runtimeOverheadPercent() + "%"),
                metric("Build time", "~" + estimate.buildSeconds() + " seconds"));
        impact.setAlignment(Pos.CENTER_LEFT);

        VBox settings = Ui.section("Build output", "The input JAR is never overwritten.",
                Ui.fieldRow("Protected JAR", outputRow), impact);

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.progressProperty().bind(context.projectState().buildProgressProperty());
        status.textProperty().bind(context.projectState().buildStatusProperty());
        build = Ui.button("Build protected JAR", "primary-button", context.buildController()::build);
        cancel = Ui.button("Cancel build", "danger-button", context.buildController()::cancel);
        reveal = Ui.button("Reveal output", "secondary-button", this::revealOutput);
        build.disableProperty().bind(context.projectState().busyProperty());
        cancel.visibleProperty().bind(context.projectState().busyProperty());
        cancel.managedProperty().bind(cancel.visibleProperty());
        cancel.disableProperty().bind(context.projectState().buildStatusProperty().isEqualTo("Cancelling…"));
        reveal.visibleProperty().bind(context.projectState().buildSuccessfulProperty());
        reveal.managedProperty().bind(reveal.visibleProperty());
        FlowPane actions = new FlowPane(Ui.SPACE_3, Ui.SPACE_2, build, cancel, reveal);
        actions.setAlignment(Pos.CENTER_RIGHT);
        status.setWrapText(true);
        VBox statusRow = new VBox(Ui.SPACE_3, status, actions);
        VBox progressSection = Ui.section("Build progress",
                "Validation runs before the engine starts. Cancellation interrupts the active build task.",
                progress, statusRow);

        VBox timeline = Ui.section("Build timeline", "Only completed attempts appear here.", history);
        content.getChildren().addAll(
                Ui.pageHeader("Build", "Validate, run, cancel, and reveal a protected artifact from one clear workspace."),
                settings, progressSection, timeline);

        context.projectState().buildHistory().addListener((javafx.collections.ListChangeListener<BuildRecord>) change -> refreshHistory());
        refreshHistory();
    }

    private void refreshHistory() {
        history.getChildren().clear();
        if (context.projectState().buildHistory().isEmpty()) {
            history.getChildren().add(Ui.label("No build attempts yet. Start a build when validation is clear.", "empty-state-copy"));
            return;
        }
        for (BuildRecord record : context.projectState().buildHistory()) {
            String tone = switch (record.status()) {
                case SUCCESS -> "success";
                case FAILED -> "error";
                default -> "warning";
            };
            StatusChip chip = new StatusChip(record.status().name(), tone);
            Label title = Ui.label(record.message(), "build-record-title");
            Label details = Ui.label(record.time().format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
                    + " · " + record.duration().toSeconds() + " seconds"
                    + (record.output() == null ? "" : " · " + record.output().getFileName()), "muted-text");
            title.setWrapText(true);
            details.setWrapText(true);
            title.setMinWidth(0);
            details.setMinWidth(0);
            VBox copy = new VBox(Ui.SPACE_1, title, details);
            copy.setMinWidth(0);
            HBox.setHgrow(copy, Priority.ALWAYS);
            HBox row = new HBox(Ui.SPACE_3, chip, copy);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("build-record");
            history.getChildren().add(row);
        }
    }

    private void revealOutput() {
        try {
            var output = context.projectState().outputPath();
            if (output == null || !Files.exists(output)) {
                throw new IllegalStateException("The completed output is no longer available.");
            }
            if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Reveal output is not supported on this system.");
            Desktop.getDesktop().open(output.getParent().toFile());
        } catch (Exception exception) {
            context.dialogs().error("Could not reveal output", exception);
        }
    }

    private static Node metric(String title, String value) {
        return new VBox(Ui.SPACE_1, Ui.label(value, "metric-value"), Ui.label(title, "metric-label"));
    }

    @Override
    public Node root() { return root; }
}
