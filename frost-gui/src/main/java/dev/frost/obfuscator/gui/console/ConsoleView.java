package dev.frost.obfuscator.gui.console;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.Ui;
import javafx.animation.PauseTransition;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.reactfx.EventStreams;
import javafx.util.Duration;

import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class ConsoleView {
    private final AppContext context;
    private final BorderPane root = new BorderPane();
    private final TextField search = new TextField();
    private final CustomComboBox<String> level =
            new CustomComboBox<>(List.of("All levels", "Debug", "Info", "Warning", "Error", "Success"));
    private final CustomComboBox<String> transformer = new CustomComboBox<>(List.of("All transformers"));
    private final CheckBox autoScroll = new CheckBox("Auto-scroll");
    private final CheckBox wordWrap = new CheckBox("Word wrap");
    private final StyleClassedTextArea output = new StyleClassedTextArea();
    private final FilteredList<LogEntry> filtered;
    private final Label empty = Ui.label("Build output will appear here", "console-empty-title");
    private final PauseTransition rebuildDelay = new PauseTransition(Duration.millis(40));
    private boolean active;
    private boolean rebuildPending = true;

    public ConsoleView(AppContext context) {
        this.context = context;
        root.getStyleClass().add("console-workspace");
        search.getStyleClass().add("text-input");
        search.setPromptText("Search logs");
        search.setMinWidth(180);
        search.setPrefWidth(300);
        search.setMaxWidth(380);
        level.setMinWidth(124);
        level.setPrefWidth(132);
        transformer.setMinWidth(180);
        transformer.setPrefWidth(210);
        autoScroll.setSelected(context.preferences().getBoolean("console.autoScroll", true));
        wordWrap.setSelected(context.preferences().getBoolean("console.wordWrap", true));
        autoScroll.selectedProperty().addListener((obs, old, value) ->
                context.preferences().putBoolean("console.autoScroll", value));
        wordWrap.selectedProperty().addListener((obs, old, value) ->
                context.preferences().putBoolean("console.wordWrap", value));
        Button copy = Ui.button("Copy", "secondary-button", this::copy);
        Button export = Ui.button("Export", "secondary-button", this::export);
        Button clear = Ui.button("Clear", "secondary-button", context.consoleModel()::clear);
        FlowPane filters = new FlowPane(Ui.SPACE_3, Ui.SPACE_2,
                search, level, transformer, autoScroll, wordWrap);
        filters.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(Ui.SPACE_3, copy, export, clear);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox toolbar = new VBox(Ui.SPACE_3, filters, actions);
        toolbar.getStyleClass().add("console-toolbar");
        root.setTop(toolbar);

        filtered = new FilteredList<>(context.consoleModel().entries(), entry -> true);
        output.setEditable(false);
        output.wrapTextProperty().bind(wordWrap.selectedProperty());
        output.getStyleClass().add("console-rich-area");
        output.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !output.getSelectedText().isBlank()) {
                context.notifications().show("Selected console reference copied");
                ClipboardContent content = new ClipboardContent();
                content.putString(output.getSelectedText());
                Clipboard.getSystemClipboard().setContent(content);
            }
        });
        Label emptyCopy = Ui.label(
                "Run validation or start a build. Use filters to focus on a transformer or log level.",
                "console-empty-copy");
        emptyCopy.setWrapText(true);
        emptyCopy.setMaxWidth(560);
        VBox emptyState = new VBox(Ui.SPACE_2, empty, emptyCopy);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(filtered));
        emptyState.managedProperty().bind(emptyState.visibleProperty());
        StackPane stack = new StackPane(output, emptyState);
        root.setCenter(stack);

        EventStreams.valuesOf(search.textProperty())
                .successionEnds(java.time.Duration.ofMillis(180))
                .subscribe(value -> applyFilter());
        level.valueProperty().addListener((obs, old, value) -> applyFilter());
        transformer.valueProperty().addListener((obs, old, value) -> applyFilter());
        rebuildDelay.setOnFinished(event -> {
            if (active) {
                rebuildPending = false;
                rebuildOutput();
            }
        });
        filtered.addListener((javafx.collections.ListChangeListener<LogEntry>) change -> scheduleRebuild());
        context.consoleModel().entries().addListener((javafx.collections.ListChangeListener<LogEntry>) change -> {
            refreshTransformers();
        });
        rebuildOutput();
    }

    private void applyFilter() {
        String query = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        String selectedLevel = level.getValue() == null ? "All levels" : level.getValue();
        String selectedTransformer = transformer.getValue() == null ? "All transformers" : transformer.getValue();
        filtered.setPredicate(entry -> (query.isBlank() || entry.message().toLowerCase(Locale.ROOT).contains(query)
                || entry.transformer().toLowerCase(Locale.ROOT).contains(query))
                && (selectedLevel.equals("All levels") || entry.level().name().equalsIgnoreCase(selectedLevel))
                && (selectedTransformer.equals("All transformers") || entry.transformer().equals(selectedTransformer)));
    }

    private void refreshTransformers() {
        String selected = transformer.getValue();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add("All transformers");
        context.consoleModel().entries().stream().map(LogEntry::transformer)
                .filter(value -> !value.isBlank()).forEach(values::add);
        transformer.setValues(values);
        if (selected != null && values.contains(selected)) transformer.setValue(selected);
    }

    private void rebuildOutput() {
        output.clear();
        DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        for (LogEntry entry : filtered) {
            int timeStart = output.getLength();
            String timestamp = entry.timestamp().format(clock);
            output.appendText(timestamp);
            output.setStyleClass(timeStart, output.getLength(), "console-time");

            output.appendText("  ");
            int levelStart = output.getLength();
            String levelText = String.format("● %-7s", entry.level().name());
            output.appendText(levelText);
            output.setStyleClass(levelStart, output.getLength(),
                    "console-" + entry.level().name().toLowerCase(Locale.ROOT));

            if (!entry.transformer().isBlank()) {
                output.appendText(" ");
                int transformerStart = output.getLength();
                output.appendText("[" + entry.transformer() + "]");
                output.setStyleClass(transformerStart, output.getLength(), "console-transformer");
            }

            output.appendText("  ");
            appendMessage(entry);
            output.appendText(System.lineSeparator());
        }
        if (autoScroll.isSelected() && output.getLength() > 0) {
            output.moveTo(output.getLength());
            output.requestFollowCaret();
        }
    }

    private void appendMessage(LogEntry entry) {
        String message = displayMessage(entry);
        String reference = entry.reference();
        int referenceStart = reference.isBlank() ? -1 : message.indexOf(reference);
        if (referenceStart < 0) {
            appendStyled(message, "console-message");
            return;
        }
        appendStyled(message.substring(0, referenceStart), "console-message");
        appendStyled(reference, "console-reference");
        appendStyled(message.substring(referenceStart + reference.length()), "console-message");
    }

    private void appendStyled(String text, String styleClass) {
        if (text.isEmpty()) return;
        int start = output.getLength();
        output.appendText(text);
        output.setStyleClass(start, output.getLength(), styleClass);
    }

    private static String displayMessage(LogEntry entry) {
        if (entry.transformer().isBlank()) return entry.message();
        String prefix = "[" + entry.transformer() + "]";
        return entry.message().startsWith(prefix)
                ? entry.message().substring(prefix.length()).stripLeading()
                : entry.message();
    }

    private void scheduleRebuild() {
        rebuildPending = true;
        if (!active) return;
        rebuildDelay.playFromStart();
    }

    public void setActive(boolean active) {
        this.active = active;
        if (active && rebuildPending) {
            rebuildDelay.stop();
            rebuildPending = false;
            rebuildOutput();
        } else if (!active) {
            rebuildDelay.stop();
        }
    }

    private void copy() {
        String text = filtered.stream().map(this::format).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        context.notifications().show(filtered.size() + " log entries copied");
    }

    private void export() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export console log");
        chooser.setInitialFileName("frostfuscator-build.log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Log files", "*.log", "*.txt"));
        var file = chooser.showSaveDialog(context.stage());
        if (file == null) return;
        try {
            Files.writeString(file.toPath(), filtered.stream().map(this::format)
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
            context.notifications().show("Console log exported");
        } catch (Exception exception) {
            context.dialogs().error("Could not export console", exception);
        }
    }

    private String format(LogEntry entry) {
        return "[" + entry.timestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "] ["
                + entry.level() + "] " + entry.message();
    }

    public Node root() { return root; }
}
