package dev.frost.obfuscator.gui.component;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

public final class CustomComboBox<T> extends MenuButton {
    private final List<T> values = new ArrayList<>();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>(this, "value");
    private final Function<T, String> formatter;
    private final Function<T, String> itemFormatter;
    private final Label displayLabel = new Label();

    public CustomComboBox(Collection<T> values) {
        this(values, String::valueOf);
    }

    public CustomComboBox(Collection<T> values, Function<T, String> formatter) {
        this(values, formatter, formatter);
    }

    public CustomComboBox(Collection<T> values, Function<T, String> formatter,
                          Function<T, String> itemFormatter) {
        this.formatter = formatter;
        this.itemFormatter = itemFormatter;
        getStyleClass().add("custom-combo");
        setMinWidth(Region.USE_PREF_SIZE);
        setMaxWidth(Double.MAX_VALUE);
        setTextOverrun(OverrunStyle.CLIP);
        displayLabel.getStyleClass().add("custom-combo-value");
        displayLabel.setMaxWidth(Double.MAX_VALUE);
        displayLabel.setTextOverrun(OverrunStyle.CLIP);
        setGraphic(displayLabel);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        value.addListener((obs, old, selected) -> syncText());
        setValues(values);
        syncText();
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DOWN) {
                move(1);
                event.consume();
            } else if (event.getCode() == KeyCode.UP) {
                move(-1);
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                show();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                hide();
            }
        });
    }

    public void setValues(Collection<T> items) {
        values.clear();
        values.addAll(items);
        getItems().clear();
        for (T item : values) {
            MenuItem menuItem = new MenuItem(itemFormatter.apply(item));
            menuItem.setOnAction(event -> setValue(item));
            getItems().add(menuItem);
        }
        if (getValue() == null && !values.isEmpty()) setValue(values.getFirst());
        else syncText();
    }

    public List<T> getValues() { return List.copyOf(values); }

    private void move(int offset) {
        if (values.isEmpty()) return;
        int current = Math.max(0, values.indexOf(getValue()));
        setValue(values.get(Math.floorMod(current + offset, values.size())));
    }

    public ObjectProperty<T> valueProperty() { return value; }
    public T getValue() { return value.get(); }
    public void setValue(T selected) { value.set(selected); }

    private void syncText() {
        T selected = getValue();
        String display = selected == null ? "Choose…" : formatter.apply(selected);
        setText(display);
        displayLabel.setText(display);
    }
}
