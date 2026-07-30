package dev.frost.obfuscator.gui.component;

import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Lightweight JavaFX autocomplete without a third-party control stylesheet. */
public final class TextFieldAutoComplete {
    private static final int MAX_RESULTS = 8;

    private TextFieldAutoComplete() {
    }

    public static void install(TextField field, Collection<String> candidates) {
        Objects.requireNonNull(field, "field");
        List<String> values = candidates == null ? List.of() : candidates.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (values.isEmpty()) return;

        ContextMenu suggestions = new ContextMenu();
        suggestions.getStyleClass().add("text-autocomplete-popup");

        Runnable refresh = () -> {
            if (!field.isFocused() || field.getScene() == null || field.getScene().getWindow() == null) {
                suggestions.hide();
                return;
            }
            String query = field.getText() == null ? "" : field.getText().strip().toLowerCase(Locale.ROOT);
            if (query.isEmpty()) {
                suggestions.hide();
                return;
            }
            List<String> matches = values.stream()
                    .filter(value -> !value.equalsIgnoreCase(query) && value.toLowerCase(Locale.ROOT).contains(query))
                    .sorted(Comparator.comparingInt(value -> value.toLowerCase(Locale.ROOT).startsWith(query) ? 0 : 1))
                    .limit(MAX_RESULTS)
                    .toList();
            suggestions.getItems().setAll(matches.stream().map(value -> item(field, suggestions, value)).toList());
            if (matches.isEmpty()) suggestions.hide();
            else if (!suggestions.isShowing()) suggestions.show(field, Side.BOTTOM, 0, 2);
        };

        field.textProperty().addListener((obs, old, value) -> refresh.run());
        field.focusedProperty().addListener((obs, old, focused) -> {
            if (focused) refresh.run();
            else suggestions.hide();
        });
        field.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && suggestions.isShowing()) {
                suggestions.hide();
                event.consume();
            }
        });
    }

    private static MenuItem item(TextField field, ContextMenu suggestions, String value) {
        MenuItem item = new MenuItem(value);
        item.setOnAction(event -> {
            field.setText(value);
            field.positionCaret(value.length());
            suggestions.hide();
        });
        return item;
    }
}
