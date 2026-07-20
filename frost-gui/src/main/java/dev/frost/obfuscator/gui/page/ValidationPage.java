package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.StatusChip;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.gui.validation.Problem;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

public final class ValidationPage implements PageView {
    private final AppContext context;
    private final Consumer<PageId> navigate;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);
    private final VBox problems = new VBox(Ui.SPACE_3);
    private final CustomComboBox<String> filter =
            new CustomComboBox<>(List.of("All problems", "Errors", "Warnings", "Recommendations"));
    private boolean active;
    private boolean refreshPending;

    public ValidationPage(AppContext context, Consumer<PageId> navigate) {
        this.context = context;
        this.navigate = navigate;
        SmoothScroll.install(root, context.themeManager());
        content.getStyleClass().addAll("page", "validation-page");
        content.setPadding(Ui.pageInsets());
        Button validate = Ui.button("Validate now", "primary-button", () -> {
            context.validationCoordinator().validateNow();
            refresh();
            context.notifications().show("Validation refreshed");
        });
        HBox tools = new HBox(Ui.SPACE_3, filter, Ui.spacer(), validate);
        tools.setAlignment(Pos.CENTER_LEFT);
        filter.valueProperty().addListener((obs, old, value) -> refresh());
        content.getChildren().addAll(
                Ui.pageHeader("Validation", "Continuous checks explain risks and provide focused quick fixes."),
                tools,
                Ui.section("Problems", "Errors block builds; warnings and recommendations help you improve safety.", problems));
        context.projectState().problems().addListener(
                (javafx.collections.ListChangeListener<Problem>) change -> requestRefresh());
        refresh();
    }

    private void refresh() {
        refreshPending = false;
        problems.getChildren().clear();
        List<Problem> visible = context.projectState().problems().stream().filter(this::matches).toList();
        if (visible.isEmpty()) {
            problems.getChildren().addAll(new StatusChip("Ready", "success"),
                    Ui.label("No problems match this filter.", "empty-state-title"),
                    Ui.label("Frostfuscator will continue validating as project and protection settings change.",
                            "empty-state-copy"));
            return;
        }
        visible.forEach(problem -> problems.getChildren().add(problemRow(problem)));
    }

    private void requestRefresh() {
        if (active) refresh();
        else refreshPending = true;
    }

    private boolean matches(Problem problem) {
        return switch (filter.getValue() == null ? "All problems" : filter.getValue()) {
            case "Errors" -> problem.severity() == Problem.Severity.ERROR;
            case "Warnings" -> problem.severity() == Problem.Severity.WARNING;
            case "Recommendations" -> problem.severity() == Problem.Severity.RECOMMENDATION;
            default -> true;
        };
    }

    private Node problemRow(Problem problem) {
        String tone = switch (problem.severity()) {
            case ERROR -> "error";
            case WARNING -> "warning";
            default -> "info";
        };
        String severity = problem.severity().name().toLowerCase(java.util.Locale.ROOT);
        StatusChip chip = new StatusChip(Character.toUpperCase(severity.charAt(0)) + severity.substring(1), tone);
        Label title = Ui.label(problem.title(), "problem-title");
        Label explanation = Ui.label(problem.explanation(), "problem-copy");
        title.setWrapText(true);
        explanation.setWrapText(true);
        title.setMinWidth(0);
        explanation.setMinWidth(0);
        VBox copy = new VBox(Ui.SPACE_1, title, explanation);
        copy.setMinWidth(0);
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox row = new HBox(Ui.SPACE_4, chip, copy);
        row.setMinWidth(0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("problem-detail-row", "problem-" + tone);
        if (problem.hasQuickFix()) {
            Button fix = Ui.button(problem.quickFixLabel(), "secondary-button", () -> {
                problem.quickFix().accept(context.projectState());
                context.validationCoordinator().validateNow();
                context.notifications().show("Quick fix applied");
            });
            fix.setMinWidth(Region.USE_PREF_SIZE);
            fix.setMaxWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(fix);
        } else if (problem.id().equals("select-input") || problem.id().equals("libraries")) {
            Button fix = Ui.button(problem.quickFixLabel(), "secondary-button", () -> navigate.accept(PageId.INPUT));
            fix.setMinWidth(Region.USE_PREF_SIZE);
            fix.setMaxWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(fix);
        }
        return row;
    }

    @Override
    public Node root() { return root; }

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
