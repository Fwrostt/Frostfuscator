package dev.frost.obfuscator.gui.console;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.Ui;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import org.reactfx.EventStreams;

import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ConsoleView {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final AppContext context;
    private final BorderPane root = new BorderPane();
    private final TextField search = new TextField();
    private final CustomComboBox<String> level =
            new CustomComboBox<>(List.of("All levels", "Debug", "Info", "Warning", "Error", "Success"));
    private final CustomComboBox<String> transformer = new CustomComboBox<>(List.of("All transformers"));
    private final CheckBox autoScroll = new CheckBox("Auto-scroll");
    private final CheckBox wordWrap = new CheckBox("Word wrap");
    private final ListView<LogEntry> output = new ListView<>();
    private final FilteredList<LogEntry> filtered;
    private boolean active;
    private boolean revealScrollPending;

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
        wordWrap.selectedProperty().addListener((obs, old, value) -> {
            context.preferences().putBoolean("console.wordWrap", value);
            output.refresh();
        });

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
        output.setItems(filtered);
        output.setCellFactory(ignored -> new ConsoleCell());
        output.getStyleClass().add("console-list");

        Label empty = Ui.label("Build output will appear here", "console-empty-title");
        Label emptyCopy = Ui.label(
                "Run validation or start a build. Use filters to focus on a transformer or log level.",
                "console-empty-copy");
        emptyCopy.setWrapText(true);
        emptyCopy.setMaxWidth(560);
        VBox emptyState = new VBox(Ui.SPACE_2, empty, emptyCopy);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.visibleProperty().bind(Bindings.isEmpty(filtered));
        emptyState.managedProperty().bind(emptyState.visibleProperty());
        root.setCenter(new StackPane(output, emptyState));

        EventStreams.valuesOf(search.textProperty())
                .successionEnds(java.time.Duration.ofMillis(180))
                .subscribe(value -> applyFilter());
        level.valueProperty().addListener((obs, old, value) -> applyFilter());
        transformer.valueProperty().addListener((obs, old, value) -> applyFilter());
        context.consoleModel().entries().addListener(
                (javafx.collections.ListChangeListener<LogEntry>) change -> {
                    refreshTransformers();
                    scrollToLatest();
                });
        refreshTransformers();
    }

    private void applyFilter() {
        String query = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        String selectedLevel = level.getValue() == null ? "All levels" : level.getValue();
        String selectedTransformer = transformer.getValue() == null ? "All transformers" : transformer.getValue();
        filtered.setPredicate(entry -> (query.isBlank() || entry.message().toLowerCase(Locale.ROOT).contains(query)
                || entry.transformer().toLowerCase(Locale.ROOT).contains(query))
                && (selectedLevel.equals("All levels") || entry.level().name().equalsIgnoreCase(selectedLevel))
                && (selectedTransformer.equals("All transformers") || entry.transformer().equals(selectedTransformer)));
        scrollToLatest();
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

    private void scrollToLatest() {
        if (!active || !autoScroll.isSelected() || filtered.isEmpty()) return;
        Platform.runLater(this::scrollWhenVisible);
    }

    private void scrollWhenVisible() {
        if (!active || !autoScroll.isSelected() || filtered.isEmpty()) return;
        Node application = root;
        while (application.getParent() != null
                && !application.getStyleClass().contains("window-root")) {
            application = application.getParent();
        }
        if (application.getOpacity() < 0.99) {
            if (revealScrollPending) return;
            revealScrollPending = true;
            Node observed = application;
            ChangeListener<Number> listener = new ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends Number> observable,
                                    Number oldValue, Number newValue) {
                    if (newValue.doubleValue() < 0.99) return;
                    observed.opacityProperty().removeListener(this);
                    revealScrollPending = false;
                    Platform.runLater(ConsoleView.this::scrollWhenVisible);
                }
            };
            observed.opacityProperty().addListener(listener);
            return;
        }
        output.scrollTo(filtered.size() - 1);
    }

    public void setActive(boolean active) {
        this.active = active;
        if (active) scrollToLatest();
    }

    private void copy() {
        String text = filtered.stream().map(this::format)
                .collect(Collectors.joining(System.lineSeparator()));
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
                    .collect(Collectors.joining(System.lineSeparator())));
            context.notifications().show("Console log exported");
        } catch (Exception exception) {
            context.dialogs().error("Could not export console", exception);
        }
    }

    private String format(LogEntry entry) {
        return "[" + entry.timestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "] ["
                + entry.level() + "] " + entry.message();
    }

    public Node root() {
        return root;
    }

    private final class ConsoleCell extends ListCell<LogEntry> {
        private final Label time = new Label();
        private final Label severity = new Label();
        private final Label source = new Label();
        private final TextFlow message = new TextFlow();
        private final HBox row = new HBox(Ui.SPACE_3, time, severity, source, message);

        private ConsoleCell() {
            time.getStyleClass().add("console-time");
            time.setMinWidth(92);
            severity.getStyleClass().add("console-level");
            severity.setMinWidth(82);
            source.getStyleClass().add("console-transformer");
            source.setMaxWidth(210);
            source.setMinWidth(Region.USE_PREF_SIZE);
            message.getStyleClass().add("console-message-flow");
            HBox.setHgrow(message, Priority.ALWAYS);
            row.setAlignment(Pos.TOP_LEFT);
            row.setMinWidth(0);
            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            setOnMouseClicked(event -> {
                LogEntry entry = getItem();
                if (event.getClickCount() == 2 && entry != null) {
                    String value = entry.reference().isBlank() ? entry.message() : entry.reference();
                    ClipboardContent content = new ClipboardContent();
                    content.putString(value);
                    Clipboard.getSystemClipboard().setContent(content);
                    context.notifications().show(entry.reference().isBlank()
                            ? "Console message copied" : "Console reference copied");
                }
            });
        }

        @Override
        protected void updateItem(LogEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }

            time.setText(entry.timestamp().format(CLOCK));
            severity.setText("\u25CF " + entry.level().name());
            severity.getStyleClass().removeAll(
                    "console-debug", "console-info", "console-warning", "console-error", "console-success");
            severity.getStyleClass().add("console-" + entry.level().name().toLowerCase(Locale.ROOT));
            source.setText(entry.transformer().isBlank() ? "" : "[" + entry.transformer() + "]");
            source.setManaged(!entry.transformer().isBlank());
            source.setVisible(!entry.transformer().isBlank());
            rebuildMessage(entry);

            boolean wrap = wordWrap.isSelected();
            message.setMinWidth(wrap ? 0 : Region.USE_PREF_SIZE);
            setGraphic(row);
        }

        private void rebuildMessage(LogEntry entry) {
            message.getChildren().clear();
            String value = displayMessage(entry);
            String reference = entry.reference();
            int referenceStart = reference.isBlank() ? -1 : value.indexOf(reference);
            if (referenceStart < 0) {
                message.getChildren().add(styledText(value, "console-message"));
                return;
            }
            addText(value.substring(0, referenceStart), "console-message");
            addText(reference, "console-reference");
            addText(value.substring(referenceStart + reference.length()), "console-message");
        }

        private void addText(String value, String styleClass) {
            if (!value.isEmpty()) message.getChildren().add(styledText(value, styleClass));
        }
    }

    private static Text styledText(String value, String styleClass) {
        Text text = new Text(value);
        text.getStyleClass().add(styleClass);
        return text;
    }

    private static String displayMessage(LogEntry entry) {
        if (entry.transformer().isBlank()) return entry.message();
        String prefix = "[" + entry.transformer() + "]";
        return entry.message().startsWith(prefix)
                ? entry.message().substring(prefix.length()).stripLeading()
                : entry.message();
    }
}
