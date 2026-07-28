package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.analysis.ProjectAnalysis;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.StatusChip;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.controlsfx.control.textfield.TextFields;

import java.nio.file.Path;

public final class InputDependenciesPage implements PageView {
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);
    private final TextField input = new TextField();
    private final TextField output = new TextField();
    private final TextField libraries = new TextField();
    private final Label analysisStatus = Ui.label("Waiting for an input JAR", "section-description");
    private final ProgressBar analysisProgress = new ProgressBar(0);
    private final VBox detected = new VBox(Ui.SPACE_3);
    private final VBox dependencyList = new VBox(Ui.SPACE_2);
    private final VBox suggestions = new VBox(Ui.SPACE_2);

    public InputDependenciesPage(AppContext context) {
        this.context = context;
        SmoothScroll.install(root, context.themeManager());
        content.getStyleClass().addAll("page", "input-page");
        content.setPadding(Ui.pageInsets());
        input.getStyleClass().add("text-input");
        output.getStyleClass().add("text-input");
        libraries.getStyleClass().add("text-input");
        input.setPromptText("Select a Java archive");
        output.setPromptText("Suggested after analysis");
        libraries.setPromptText("Optional fallback library path");
        input.setText(nullToEmpty(context.projectState().configuration().getInput()));
        output.setText(nullToEmpty(context.projectState().configuration().getOutput()));
        libraries.setText(nullToEmpty(context.projectState().configuration().getLibs()));
        TextFields.bindAutoCompletion(input, context.preferences().recentProjects());

        HBox inputRow = fileRow(input, "Choose JAR", this::chooseInput);
        HBox outputRow = fileRow(output, "Choose output", this::chooseOutput);
        HBox librariesRow = fileRow(libraries, "Choose folder", this::chooseLibraries);
        Button analyze = Ui.button("Analyze selected JAR", "primary-button", this::analyze);
        analysisProgress.setVisible(false);
        analysisProgress.setManaged(false);
        HBox status = new HBox(Ui.SPACE_3, analysisProgress, analysisStatus, Ui.spacer(), analyze);
        status.setAlignment(Pos.CENTER_LEFT);

        VBox source = Ui.section("Project files", "Frostfuscator analyzes the input and proposes the rest.",
                Ui.fieldRow("Input JAR", inputRow),
                Ui.fieldRow("Protected output", outputRow),
                Ui.fieldRow("Library fallback", librariesRow),
                status);

        GridPane analysisGrid = new GridPane();
        analysisGrid.setHgap(Ui.SPACE_8);
        analysisGrid.setVgap(Ui.SPACE_6);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(55);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(45);
        analysisGrid.getColumnConstraints().addAll(left, right);
        analysisGrid.add(Ui.section("Detected project", "Archive structure, runtime, frameworks, and runtime-sensitive features.", detected), 0, 0);
        analysisGrid.add(Ui.section("Recommendations", "Safe values proposed from the analysis.", suggestions), 1, 0);
        analysisGrid.add(Ui.section("Resolved dependencies", "Manifest, adjacent folders, nested JARs, caches, and runtime.", dependencyList), 0, 1, 2, 1);

        CheckBox recursive = new CheckBox("Scan library folders recursively");
        CheckBox runtime = new CheckBox("Load Java runtime classes");
        CheckBox strict = new CheckBox("Fail the build when a declared library cannot be loaded");
        CheckBox autoDetect = new CheckBox("Skip detected shaded and supplied library classes");
        ObfuscationConfig.LibraryConfig libraryConfig = context.projectState().configuration().getLibraries();
        recursive.setSelected(libraryConfig.isRecursive());
        runtime.setSelected(libraryConfig.isRuntime());
        strict.setSelected(libraryConfig.isStrict());
        autoDetect.setSelected(libraryConfig.isAutoDetect());
        recursive.selectedProperty().addListener((obs, old, value) -> { libraryConfig.setRecursive(value); context.projectState().touch(); });
        runtime.selectedProperty().addListener((obs, old, value) -> { libraryConfig.setRuntime(value); context.projectState().touch(); });
        strict.selectedProperty().addListener((obs, old, value) -> { libraryConfig.setStrict(value); context.projectState().touch(); });
        autoDetect.selectedProperty().addListener((obs, old, value) -> { libraryConfig.setAutoDetect(value); context.projectState().touch(); });

        VBox fallback = Ui.section("Manual dependency fallback",
                "Automatic resolution runs first. Use these controls only for unusual layouts.",
                autoDetect, recursive, runtime, strict);

        content.getChildren().addAll(
                Ui.pageHeader("Input & Dependencies", "Inspect the selected JAR, resolve its runtime graph, and start from safe suggestions."),
                source, analysisGrid, fallback);

        input.textProperty().addListener((obs, old, value) -> {
            context.projectState().configuration().setInput(value.trim());
            context.projectState().touch();
        });
        output.textProperty().addListener((obs, old, value) -> {
            context.projectState().configuration().setOutput(value.trim());
            context.projectState().touch();
        });
        libraries.textProperty().addListener((obs, old, value) -> {
            context.projectState().configuration().setLibs(value.trim());
            context.projectState().touch();
        });
        context.projectState().analysisProperty().addListener((obs, old, value) -> refresh(value));
        refresh(context.projectState().analysis());
    }

    private HBox fileRow(TextField field, String action, Runnable handler) {
        HBox.setHgrow(field, Priority.ALWAYS);
        Button browse = Ui.button(action, "secondary-button", handler);
        HBox row = new HBox(Ui.SPACE_3, field, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private void chooseInput() {
        context.dialogs().openJar().ifPresent(path -> {
            input.setText(path.toAbsolutePath().toString());
            analyze();
        });
    }

    private void chooseOutput() {
        String name = context.projectState().analysis().suggestedOutput();
        String suggested = name.isBlank() ? "protected.jar" : Path.of(name).getFileName().toString();
        context.dialogs().saveJar(suggested).ifPresent(path -> output.setText(path.toAbsolutePath().toString()));
    }

    private void chooseLibraries() {
        context.dialogs().chooseDirectory("Choose dependency folder")
                .ifPresent(path -> libraries.setText(path.toAbsolutePath().toString()));
    }

    private void analyze() {
        String value = input.getText().trim();
        if (value.isBlank()) {
            context.notifications().show("Choose an input JAR before analyzing");
            return;
        }
        analysisStatus.setText("Inspecting classes, manifest, frameworks, and dependencies…");
        analysisProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        analysisProgress.setVisible(true);
        analysisProgress.setManaged(true);
        Task<ProjectAnalysis> task = new Task<>() {
            @Override
            protected ProjectAnalysis call() throws Exception {
                return context.jarAnalyzer().analyze(Path.of(value));
            }

            @Override
            protected void succeeded() {
                ProjectAnalysis analysis = getValue();
                context.projectState().setAnalysis(analysis);
                context.configurationBinder().applyAnalysisSuggestions(analysis);
                output.setText(context.projectState().configuration().getOutput());
                context.preferences().rememberProject(analysis.jar().toString());
                analysisStatus.setText("Analysis complete");
                analysisProgress.setVisible(false);
                analysisProgress.setManaged(false);
                context.validationCoordinator().validateNow();
                context.notifications().show("Project analysis complete");
            }

            @Override
            protected void failed() {
                analysisProgress.setVisible(false);
                analysisProgress.setManaged(false);
                analysisStatus.setText("Analysis could not complete");
                context.dialogs().error("JAR analysis failed", getException());
            }
        };
        Thread thread = new Thread(task, "frostfuscator-jar-analysis");
        thread.setDaemon(true);
        thread.start();
    }

    private void refresh(ProjectAnalysis analysis) {
        detected.getChildren().clear();
        dependencyList.getChildren().clear();
        suggestions.getChildren().clear();
        if (!analysis.analyzed()) {
            detected.getChildren().add(Ui.label("Select an input JAR to detect project details.", "empty-state-copy"));
            dependencyList.getChildren().add(Ui.label("Dependencies will be resolved automatically after analysis.", "empty-state-copy"));
            suggestions.getChildren().add(Ui.label("Output paths, rules, and dictionaries will be proposed here.", "empty-state-copy"));
            return;
        }
        detected.getChildren().addAll(
                data("Java target", analysis.javaVersion() == 0 ? "Unknown" : "Java " + analysis.javaVersion()),
                data("Main class", analysis.mainClass().isBlank() ? "Not declared" : analysis.mainClass()),
                data("Archive", analysis.fatJar() ? "Fat JAR" : "Standard JAR"),
                data("Classes", String.format("%,d", analysis.classCount())),
                data("Methods", String.format("%,d", analysis.inventory().methodCount())),
                data("Fields", String.format("%,d", analysis.inventory().fieldCount())),
                data("Instructions", String.format("%,d", analysis.inventory().instructionCount())),
                data("String literals", String.format("%,d occurrences · %,d unique",
                        analysis.inventory().stringLiteralCount(), analysis.inventory().uniqueStringCount())),
                data("Protection candidates", String.format("%,d virtualization · %,d outlining",
                        analysis.inventory().virtualizableMethodCount(),
                        analysis.inventory().outlineableMethodCount())),
                data("Package roots", analysis.packageRoots().isEmpty() ? "Default package" : String.join(", ", analysis.packageRoots())),
                data("Frameworks", analysis.frameworks().isEmpty() ? "None detected" : String.join(", ", analysis.frameworks())),
                flags(analysis)
        );
        suggestions.getChildren().addAll(
                data("Output", analysis.suggestedOutput()),
                data("Flatten package", analysis.suggestedPackage()),
                data("Dictionary", analysis.suggestedDictionary()),
                data("Keep rules", analysis.keepRules().isEmpty() ? "None required" : String.join(", ", analysis.keepRules())),
                data("Exclusions", analysis.exclusions().isEmpty() ? "None suggested" : String.join(", ", analysis.exclusions()))
        );
        if (analysis.resolvedLibraries().isEmpty() && analysis.unresolvedLibraries().isEmpty()) {
            dependencyList.getChildren().add(new StatusChip("No external dependencies declared", "success"));
        } else {
            analysis.resolvedLibraries().forEach(path -> dependencyList.getChildren().add(dependency(path, true)));
            analysis.unresolvedLibraries().forEach(path -> dependencyList.getChildren().add(dependency(path, false)));
        }
    }

    private static Node flags(ProjectAnalysis analysis) {
        FlowPane flags = new FlowPane(Ui.SPACE_2, Ui.SPACE_2);
        if (analysis.reflectionUsage()) flags.getChildren().add(new StatusChip("Reflection", "warning"));
        if (analysis.serviceLoaders()) flags.getChildren().add(new StatusChip("ServiceLoader", "info"));
        if (analysis.nativeLibraries()) flags.getChildren().add(new StatusChip("Native libraries", "warning"));
        if (analysis.signed()) flags.getChildren().add(new StatusChip("Signed", "warning"));
        if (flags.getChildren().isEmpty()) flags.getChildren().add(new StatusChip("No sensitive runtime features found", "success"));
        return flags;
    }

    private static Node data(String label, String value) {
        Label key = Ui.label(label, "info-key");
        Label data = Ui.label(value, "info-value");
        data.setWrapText(true);
        HBox.setHgrow(data, Priority.ALWAYS);
        HBox row = new HBox(Ui.SPACE_4, key, data);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static Node dependency(String value, boolean resolved) {
        StatusChip status = new StatusChip(resolved ? "Resolved" : "Missing", resolved ? "success" : "error");
        Label path = Ui.label(value, "dependency-path");
        path.setWrapText(true);
        HBox.setHgrow(path, Priority.ALWAYS);
        HBox row = new HBox(Ui.SPACE_3, status, path);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("dependency-row");
        return row;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    @Override
    public Node root() { return root; }
}
