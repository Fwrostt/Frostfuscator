package dev.frost.obfuscator.gui.component;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Orientation;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;

/**
 * Lightweight single-value slider with bounded layout and direct pointer tracking.
 * It avoids the native skin overflow and synchronization glitches seen in dense forms.
 */
public final class SmoothSlider extends Region {
    private static final double THUMB_SIZE = 16;
    private static final double TRACK_HEIGHT = 5;

    private final double min;
    private final double max;
    private final double step;
    private final DoubleProperty value = new SimpleDoubleProperty(this, "value");
    private final Region track = new Region();
    private final Region fill = new Region();
    private final Region thumb = new Region();

    public SmoothSlider(double min, double max, double initial, double step) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        this.step = Math.max(0.0001, step);
        getStyleClass().add("smooth-slider");
        track.getStyleClass().add("smooth-slider-track");
        fill.getStyleClass().add("smooth-slider-fill");
        thumb.getStyleClass().add("smooth-slider-thumb");
        track.setManaged(false);
        fill.setManaged(false);
        thumb.setManaged(false);
        getChildren().addAll(track, fill, thumb);
        setAccessibleRole(AccessibleRole.SLIDER);
        setFocusTraversable(true);
        setMinWidth(120);
        setPrefWidth(420);
        setMaxWidth(Double.MAX_VALUE);
        setMinHeight(32);
        setPrefHeight(32);
        setMaxHeight(32);

        value.addListener((obs, old, next) -> {
            requestLayout();
            notifyAccessibleAttributeChanged(AccessibleAttribute.VALUE);
        });
        setOnMousePressed(event -> {
            requestFocus();
            updateFromPointer(event.getX());
            event.consume();
        });
        setOnMouseDragged(event -> {
            updateFromPointer(event.getX());
            event.consume();
        });
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.DOWN) {
                setValue(getValue() - this.step);
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.UP) {
                setValue(getValue() + this.step);
                event.consume();
            } else if (event.getCode() == KeyCode.HOME) {
                setValue(this.min);
                event.consume();
            } else if (event.getCode() == KeyCode.END) {
                setValue(this.max);
                event.consume();
            }
        });
        setValue(initial);
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double centerY = getHeight() / 2d;
        double start = THUMB_SIZE / 2d;
        double available = Math.max(0, width - THUMB_SIZE);
        double fraction = (getValue() - min) / (max - min);
        double thumbCenter = start + available * fraction;

        track.resizeRelocate(start, centerY - TRACK_HEIGHT / 2d, available, TRACK_HEIGHT);
        fill.resizeRelocate(start, centerY - TRACK_HEIGHT / 2d,
                Math.max(0, thumbCenter - start), TRACK_HEIGHT);
        thumb.resizeRelocate(thumbCenter - THUMB_SIZE / 2d,
                centerY - THUMB_SIZE / 2d, THUMB_SIZE, THUMB_SIZE);
    }

    private void updateFromPointer(double x) {
        double available = Math.max(1, getWidth() - THUMB_SIZE);
        double fraction = Math.max(0, Math.min(1, (x - THUMB_SIZE / 2d) / available));
        setValue(min + fraction * (max - min));
    }

    public DoubleProperty valueProperty() { return value; }
    public double getValue() { return value.get(); }

    public void setValue(double next) {
        double snapped = Math.round((next - min) / step) * step + min;
        value.set(Math.max(min, Math.min(max, snapped)));
    }

    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        return switch (attribute) {
            case VALUE -> getValue();
            case MIN_VALUE -> min;
            case MAX_VALUE -> max;
            case ORIENTATION -> Orientation.HORIZONTAL;
            default -> super.queryAccessibleAttribute(attribute, parameters);
        };
    }
}
