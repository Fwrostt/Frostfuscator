package dev.frost.obfuscator.gui.component;

import javafx.beans.property.IntegerProperty;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

public final class LinkedSlider extends HBox {
    private final NumericField numeric;

    public LinkedSlider(int initial, int min, int max, int step, String unit, int recommended) {
        super(Ui.SPACE_3);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("linked-slider");
        setMinWidth(0);
        SmoothSlider slider = new SmoothSlider(min, max, initial, step);
        HBox.setHgrow(slider, Priority.ALWAYS);
        numeric = new NumericField(initial, min, max, step, unit, recommended);
        numeric.setMinWidth(168);
        numeric.setPrefWidth(168);
        numeric.setMaxWidth(168);
        slider.valueProperty().addListener((obs, old, value) -> {
            int snapped = Math.max(min, Math.min(max, value.intValue()));
            if (numeric.getValue() != snapped) numeric.setValue(snapped);
        });
        numeric.valueProperty().addListener((obs, old, value) -> {
            if (Math.abs(slider.getValue() - value.intValue()) > 0.001) slider.setValue(value.intValue());
        });
        getChildren().addAll(slider, numeric);
    }

    public IntegerProperty valueProperty() { return numeric.valueProperty(); }
}
