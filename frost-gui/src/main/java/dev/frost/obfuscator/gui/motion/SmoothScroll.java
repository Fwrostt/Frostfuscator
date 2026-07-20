package dev.frost.obfuscator.gui.motion;

import dev.frost.obfuscator.gui.theme.ThemeManager;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;

public final class SmoothScroll {
    private static final String INSTALLED_KEY = SmoothScroll.class.getName() + ".installed";
    private static final double PIXELS_PER_LINE = 46;
    private static final Duration DURATION = Duration.millis(175);
    private static final Interpolator EASING = Interpolator.SPLINE(0.22, 1, 0.36, 1);

    private SmoothScroll() {
    }

    public static void install(ScrollPane scroll, ThemeManager themes) {
        if (scroll.getProperties().putIfAbsent(INSTALLED_KEY, Boolean.TRUE) != null) return;
        State state = new State(scroll, themes);
        scroll.addEventFilter(ScrollEvent.SCROLL, state::handle);
    }

    private static final class State {
        private final ScrollPane scroll;
        private final ThemeManager themes;
        private Timeline animation;
        private double target;
        private boolean animating;

        private State(ScrollPane scroll, ThemeManager themes) {
            this.scroll = scroll;
            this.themes = themes;
            this.target = scroll.getVvalue();
            scroll.vvalueProperty().addListener((obs, old, value) -> {
                if (!animating) target = value.doubleValue();
            });
        }

        private void handle(ScrollEvent event) {
            if (themes.reducedMotionProperty().get()
                    || event.isInertia()
                    || event.getDeltaY() == 0
                    || Math.abs(event.getDeltaX()) > Math.abs(event.getDeltaY())
                    || scroll.getContent() == null) {
                return;
            }

            double scrollableHeight = scroll.getContent().getBoundsInLocal().getHeight()
                    - scroll.getViewportBounds().getHeight();
            if (scrollableHeight <= 0) return;

            double lineDelta = event.getTextDeltaYUnits() == ScrollEvent.VerticalTextScrollUnits.LINES
                    ? event.getTextDeltaY()
                    : event.getDeltaY() / PIXELS_PER_LINE;
            if (lineDelta == 0) return;

            target = clamp(target - (lineDelta * PIXELS_PER_LINE / scrollableHeight), 0, 1);
            if (animation != null) animation.stop();
            animating = true;
            animation = new Timeline(new KeyFrame(DURATION,
                    new KeyValue(scroll.vvalueProperty(), target, EASING)));
            animation.setOnFinished(finished -> {
                animating = false;
                target = scroll.getVvalue();
                animation = null;
            });
            animation.play();
            event.consume();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
