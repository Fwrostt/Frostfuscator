package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.build.BuildRecord;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.StatusChip;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.transformer.TransformerConfig;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

public final class ReportsPage implements PageView {
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);
    private final VBox summary = new VBox(Ui.SPACE_3);

    public ReportsPage(AppContext context) {
        this.context = context;
        SmoothScroll.install(root, context.themeManager());
        content.getStyleClass().addAll("page", "reports-page");
        content.setPadding(Ui.pageInsets());
        TransformerConfig report = context.projectState().configuration().getTransformers()
                .computeIfAbsent("statistics-report", key -> new TransformerConfig());
        CheckBox enabled = new CheckBox("Generate a report after each successful build");
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
            if (!output.getText().contains(".")) output.setText(output.getText() + "." + value);
            context.projectState().touch();
        });
        output.textProperty().addListener((obs, old, value) -> {
            report.getOptions().put("output", value.trim());
            context.projectState().touch();
        });
        VBox settings = Ui.section("Report output",
                "The existing statistics-report transformer remains the source of generated reports.",
                enabled, Ui.fieldRow("Format", format), Ui.fieldRow("Report path", output));
        content.getChildren().addAll(
                Ui.pageHeader("Reports", "Review build outcomes and configure machine-readable or HTML reports."),
                settings,
                Ui.section("Latest build summary", "Real session results appear after a build.", summary));
        context.projectState().buildHistory().addListener((javafx.collections.ListChangeListener<BuildRecord>) change -> refresh());
        refresh();
    }

    private void refresh() {
        summary.getChildren().clear();
        if (context.projectState().buildHistory().isEmpty()) {
            summary.getChildren().add(Ui.label("No build data is available yet.", "empty-state-copy"));
            return;
        }
        BuildRecord latest = context.projectState().buildHistory().getFirst();
        String tone = latest.status() == BuildRecord.Status.SUCCESS ? "success"
                : latest.status() == BuildRecord.Status.FAILED ? "error" : "warning";
        summary.getChildren().addAll(
                new StatusChip(latest.status().name(), tone),
                line("Duration", latest.duration().toSeconds() + " seconds"),
                line("Output", latest.output() == null ? "Not created" : latest.output().toString()),
                line("Message", latest.message())
        );
    }

    private static Node line(String label, String value) {
        HBox row = new HBox(Ui.SPACE_4, Ui.label(label, "info-key"), Ui.label(value, "info-value"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    @Override
    public Node root() { return root; }
}
