package dev.frost.obfuscator.gui.component;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;

public final class NumericField extends HBox {
    private final int min;
    private final int max;
    private final int step;
    private final int recommended;
    private final IntegerProperty value = new SimpleIntegerProperty();
    private final TextField editor = new TextField();

    public NumericField(int initial, int min, int max, int step, String unit, int recommended) {
        super(0);
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.recommended = recommended;
        getStyleClass().add("numeric-field");
        setAlignment(Pos.CENTER_LEFT);

        Button decrement = stepButton("fth-minus", "Decrease", -this.step);
        Button increment = stepButton("fth-plus", "Increase", this.step);
        editor.getStyleClass().add("numeric-editor");
        editor.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(editor, Priority.ALWAYS);
        Label unitLabel = new Label(unit == null ? "" : unit);
        unitLabel.getStyleClass().add("numeric-unit");
        unitLabel.setMinWidth(Region.USE_PREF_SIZE);
        unitLabel.setVisible(unit != null && !unit.isBlank());
        unitLabel.setManaged(unitLabel.isVisible());
        Button reset = iconButton("fth-rotate-ccw", "Reset to recommended value");
        reset.getStyleClass().add("numeric-reset");
        reset.setOnAction(event -> setValue(recommended));
        getChildren().addAll(decrement, editor, unitLabel, increment, reset);

        value.addListener((obs, old, current) -> {
            String text = String.valueOf(current.intValue());
            if (!editor.getText().equals(text)) editor.setText(text);
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), false);
        });
        editor.setOnAction(event -> commit());
        editor.focusedProperty().addListener((obs, old, focused) -> { if (!focused) commit(); });
        editor.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.UP) {
                adjust(this.step);
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN) {
                adjust(-this.step);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                editor.setText(String.valueOf(getValue()));
                event.consume();
            }
        });
        setValue(initial);
    }

    private Button stepButton(String icon, String accessibleText, int delta) {
        Button button = iconButton(icon, accessibleText);
        button.getStyleClass().add("numeric-step");
        button.setOnAction(event -> adjust(delta));
        return button;
    }

    private static Button iconButton(String literal, String accessibleText) {
        FontIcon icon = new FontIcon(literal);
        icon.setIconSize(14);
        icon.getStyleClass().add("numeric-icon");
        Button button = new Button();
        button.setGraphic(icon);
        button.setAccessibleText(accessibleText);
        return button;
    }

    private void adjust(int delta) {
        setValue(getValue() + delta);
    }

    private void commit() {
        try {
            int parsed = Integer.parseInt(editor.getText().trim());
            setValue(parsed);
        } catch (NumberFormatException exception) {
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), true);
        }
    }

    public IntegerProperty valueProperty() { return value; }
    public int getValue() { return value.get(); }
    public void setValue(int next) { value.set(Math.max(min, Math.min(max, next))); }
}
