package dev.frost.obfuscator.gui.motion;

import dev.frost.obfuscator.gui.theme.ThemeManager;
import javafx.animation.*;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Map;
import java.util.WeakHashMap;

public final class Motion {
    private final ThemeManager themes;
    private final Map<Node, Animation> active = new WeakHashMap<>();
    private final Interpolator easing = Interpolator.SPLINE(0.22, 1, 0.36, 1);
    private Animation pageTransition;

    public Motion(ThemeManager themes) {
        this.themes = themes;
    }

    public void pageIn(Node node) {
        stop(node);
        if (themes.reducedMotionProperty().get()) {
            node.setOpacity(1);
            node.setTranslateY(0);
            return;
        }
        node.setOpacity(0);
        node.setTranslateY(8);
        FadeTransition fade = new FadeTransition(Duration.millis(180), node);
        fade.setToValue(1);
        fade.setInterpolator(easing);
        TranslateTransition translate = new TranslateTransition(Duration.millis(180), node);
        translate.setToY(0);
        translate.setInterpolator(easing);
        play(node, new ParallelTransition(fade, translate));
    }

    public void swap(StackPane host, Node previous, Node next) {
        if (pageTransition != null) {
            pageTransition.stop();
            pageTransition = null;
        }
        for (Node child : host.getChildren()) resetPageNode(child);
        stop(next);
        if (previous != null) stop(previous);
        // Replace the scene graph atomically, then animate only properties on
        // the entering page. Keeping outgoing and incoming virtualized controls
        // under one parent during a pulse can invalidate JavaFX cached bounds.
        host.getChildren().setAll(next);
        resetPageNode(next);
        if (themes.reducedMotionProperty().get() || previous == null || previous == next) return;

        next.setOpacity(0);
        next.setTranslateX(10);
        FadeTransition inFade = new FadeTransition(Duration.millis(170), next);
        inFade.setToValue(1);
        inFade.setInterpolator(easing);
        TranslateTransition inMove = new TranslateTransition(Duration.millis(170), next);
        inMove.setToX(0);
        inMove.setInterpolator(easing);

        ParallelTransition transition = new ParallelTransition(inFade, inMove);
        transition.setOnFinished(event -> {
            if (pageTransition != transition) return;
            resetPageNode(next);
            active.remove(next);
            pageTransition = null;
        });
        active.put(next, transition);
        pageTransition = transition;
        transition.play();
    }

    public void press(Node node) {
        stop(node);
        if (themes.reducedMotionProperty().get()) return;
        ScaleTransition down = new ScaleTransition(Duration.millis(70), node);
        down.setToX(0.985);
        down.setToY(0.985);
        ScaleTransition up = new ScaleTransition(Duration.millis(90), node);
        up.setToX(1);
        up.setToY(1);
        play(node, new SequentialTransition(down, up));
    }

    private void play(Node node, Animation animation) {
        active.put(node, animation);
        animation.setOnFinished(event -> active.remove(node));
        animation.play();
    }

    private void stop(Node node) {
        Animation previous = active.remove(node);
        if (previous != null) previous.stop();
    }

    private static void resetPageNode(Node node) {
        node.setOpacity(1);
        node.setTranslateX(0);
        node.setTranslateY(0);
        node.setScaleX(1);
        node.setScaleY(1);
    }
}
