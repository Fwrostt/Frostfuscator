package dev.frost.obfuscator.gui.component;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** A bounded, theme-native searchable dropdown for large project symbol lists. */
public final class SearchableDropdown<T> extends MenuButton {
    private static final int MAX_VISIBLE_ROWS = 9;

    private final List<T> values = new ArrayList<>();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>(this, "value");
    private final Function<T, String> formatter;
    private final Label displayLabel = new Label();
    private final TextField search = new TextField();
    private final ListView<T> results = new ListView<>();
    private final VBox popup = new VBox(Ui.SPACE_2);
    private String promptText;

    public SearchableDropdown(String promptText, Function<T, String> formatter) {
        this.promptText = promptText == null ? "Choose…" : promptText;
        this.formatter = formatter == null ? String::valueOf : formatter;
        getStyleClass().addAll("custom-combo", "searchable-dropdown");
        setMinWidth(Region.USE_PREF_SIZE);
        setMaxWidth(Double.MAX_VALUE);

        displayLabel.getStyleClass().add("custom-combo-value");
        displayLabel.setMaxWidth(Double.MAX_VALUE);
        displayLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        setGraphic(displayLabel);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        search.setPromptText("Type to filter…");
        search.setAccessibleText("Filter dropdown options");
        search.getStyleClass().add("searchable-dropdown-search");
        search.textProperty().addListener((obs, old, text) -> filter(text));

        results.getStyleClass().add("searchable-dropdown-results");
        results.setPlaceholder(Ui.label("No matches", "searchable-dropdown-empty"));
        results.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : SearchableDropdown.this.formatter.apply(item));
            }
        });
        results.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1 && results.getSelectionModel().getSelectedItem() != null) selectResult();
        });
        results.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                selectResult();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                hide();
                requestFocus();
                event.consume();
            }
        });
        search.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DOWN) {
                results.requestFocus();
                results.getSelectionModel().selectFirst();
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER && !results.getItems().isEmpty()) {
                results.getSelectionModel().selectFirst();
                selectResult();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                hide();
                requestFocus();
                event.consume();
            }
        });

        VBox.setVgrow(results, Priority.ALWAYS);
        popup.getStyleClass().add("searchable-dropdown-popup");
        popup.setAlignment(Pos.TOP_LEFT);
        popup.getChildren().addAll(search, results);
        CustomMenuItem content = new CustomMenuItem(popup, false);
        content.setHideOnClick(false);
        getItems().add(content);

        value.addListener((obs, old, selected) -> syncDisplay());
        showingProperty().addListener((obs, old, showing) -> {
            if (!showing) return;
            sizePopup();
            search.clear();
            filter("");
            Platform.runLater(search::requestFocus);
        });
        widthProperty().addListener((obs, old, width) -> sizePopup());
        syncDisplay();
    }

    public void setValues(Collection<T> items) {
        T selected = getValue();
        values.clear();
        if (items != null) values.addAll(items);
        if (selected != null && !values.contains(selected)) setValue(null);
        filter(search.getText());
        sizePopup();
    }

    public List<T> getValues() { return List.copyOf(values); }

    public void setPromptText(String text) {
        promptText = text == null || text.isBlank() ? "Choose…" : text;
        syncDisplay();
    }

    public ObjectProperty<T> valueProperty() { return value; }
    public T getValue() { return value.get(); }
    public void setValue(T selected) { value.set(selected); }

    private void filter(String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<T> matches = values.stream().filter(item -> needle.isEmpty()
                || formatter.apply(item).toLowerCase(Locale.ROOT).contains(needle)).toList();
        results.getItems().setAll(matches);
        if (!matches.isEmpty()) results.getSelectionModel().selectFirst();
    }

    private void selectResult() {
        T selected = results.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        setValue(selected);
        hide();
        requestFocus();
    }

    private void sizePopup() {
        double preferred = getPrefWidth() > 0 ? getPrefWidth() : 280;
        double width = Math.max(280, Math.min(520, getWidth() > 0 ? getWidth() : preferred));
        popup.setMinWidth(width);
        popup.setPrefWidth(width);
        popup.setMaxWidth(width);
        results.setFixedCellSize(34);
        int rows = Math.max(1, Math.min(MAX_VISIBLE_ROWS, values.size()));
        results.setMinHeight(Math.min(116, rows * 34 + 2));
        results.setPrefHeight(Math.max(116, rows * 34 + 2));
        results.setMaxHeight(MAX_VISIBLE_ROWS * 34 + 2);
    }

    private void syncDisplay() {
        T selected = getValue();
        String text = selected == null ? promptText : formatter.apply(selected);
        displayLabel.setText(text);
        displayLabel.setTooltip(selected == null ? null : new Tooltip(text));
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("empty"), selected == null);
        setAccessibleText(text);
    }
}
