package dev.frost.obfuscator.gui.app;

import dev.frost.obfuscator.gui.titlebar.StartupTitleBar;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public final class StartupView extends StackPane implements AutoCloseable {
    private static final Interpolator EASE = Interpolator.SPLINE(0.22, 1, 0.36, 1);
    private static final long MINIMUM_VISIBLE_NANOS = 250_000_000L;
    private final boolean reducedMotion;
    private final long createdAt = System.nanoTime();
    private final ProgressBar progress = new ProgressBar(0.04);
    private final VBox content;
    private final List<Animation> motion = new ArrayList<>();
    private final AnimationTimer progressDriver;
    private double displayedProgress = 0.04;
    private double targetProgress = 0.04;
    private long previousFrame;
    private long largestFrameGap;
    private Runnable progressCompletion;
    private boolean revealStarted;
    private Node rotatingFrame;

    public StartupView(boolean reducedMotion) {
        this(null, reducedMotion);
    }

    public StartupView(Stage stage, boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        getStyleClass().add("startup-view");
        setAlignment(Pos.CENTER);

        Region base = new Region();
        base.getStyleClass().add("startup-backdrop");
        Pane ambient = ambientField();

        StackPane emblem = emblem();
        Label name = new Label("Frostfuscator");
        name.getStyleClass().add("startup-minimal-name");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("startup-minimal-progress");

        content = new VBox(24, emblem, name, progress);
        content.getStyleClass().add("startup-minimal-content");
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(540);
        getChildren().addAll(base, ambient, content);
        if (stage != null) {
            StartupTitleBar titleBar = new StartupTitleBar(stage);
            StackPane.setAlignment(titleBar, Pos.TOP_CENTER);
            getChildren().add(titleBar);
        }

        progressDriver = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (previousFrame == 0) {
                    previousFrame = now;
                    return;
                }
                long frameGap = now - previousFrame;
                largestFrameGap = Math.max(largestFrameGap, frameGap);
                double seconds = Math.min(0.05, frameGap / 1_000_000_000d);
                previousFrame = now;
                double remaining = targetProgress - displayedProgress;
                double step = Math.min(Math.max(0, remaining), 0.52 * seconds);
                displayedProgress += step;
                if (remaining < 0.0005) displayedProgress = targetProgress;
                progress.setProgress(displayedProgress);
                if (rotatingFrame != null) {
                    double elapsedSeconds = (now - createdAt) / 1_000_000_000d;
                    double degreesPerSecond = reducedMotion ? 12 : 68;
                    rotatingFrame.setRotate((elapsedSeconds * degreesPerSecond) % 360);
                }
                if (progressCompletion != null && displayedProgress >= 0.988) {
                    Runnable completion = progressCompletion;
                    progressCompletion = null;
                    completion.run();
                }
            }
        };
        progressDriver.start();
        if (!reducedMotion) playAmbientMotion(ambient, content);
    }

    private Pane ambientField() {
        Pane field = new Pane();
        field.getStyleClass().add("startup-ambient");
        field.setMouseTransparent(true);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(field.widthProperty());
        clip.heightProperty().bind(field.heightProperty());
        field.setClip(clip);

        Circle wash = glow("#335A6A", 0.12);
        wash.centerXProperty().bind(widthProperty().multiply(0.42));
        wash.centerYProperty().bind(heightProperty().multiply(0.44));
        wash.radiusProperty().bind(Bindings.min(widthProperty(), heightProperty()).multiply(0.62));

        field.getChildren().add(wash);
        wash.getProperties().put("motion", new double[] {-34, 20, 38, -24, 1, 1.05, 12_800});
        return field;
    }

    private static Circle glow(String color, double opacity) {
        Circle circle = new Circle();
        circle.setFill(new RadialGradient(
                0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web(color, opacity)),
                new Stop(0.48, Color.web(color, opacity * 0.42)),
                new Stop(1, Color.TRANSPARENT)
        ));
        circle.setCache(true);
        circle.setCacheHint(CacheHint.SPEED);
        return circle;
    }

    private StackPane emblem() {
        StackPane visual = new StackPane();
        visual.getStyleClass().add("startup-emblem");

        Circle orbitTrack = new Circle(88);
        orbitTrack.setFill(Color.TRANSPARENT);
        orbitTrack.setStroke(Color.web("#526168", 0.24));
        orbitTrack.setStrokeWidth(1);

        Circle orbit = new Circle(88);
        orbit.setFill(Color.TRANSPARENT);
        orbit.setStroke(Color.web("#7694A0", 0.82));
        orbit.setStrokeWidth(2);
        orbit.setStrokeLineCap(StrokeLineCap.ROUND);
        orbit.getStrokeDashArray().setAll(106d, 447d);
        orbit.getStyleClass().add("startup-rotor-orbit");

        StackPane orbitLayer = new StackPane(orbitTrack, orbit);
        orbitLayer.getStyleClass().add("startup-rotor");
        orbitLayer.setMinSize(210, 210);
        orbitLayer.setPrefSize(210, 210);
        orbitLayer.setMaxSize(210, 210);
        rotatingFrame = orbitLayer;

        Rectangle plate = new Rectangle(92, 92);
        plate.setArcWidth(22);
        plate.setArcHeight(22);
        plate.setFill(Color.web("#0B0F11"));
        plate.setStroke(Color.web("#63747B", 0.30));
        plate.setStrokeWidth(1);

        StackPane mark = new StackPane();
        mark.getStyleClass().addAll("brand-mark", "startup-core-mark");
        Region strokeA = new Region();
        Region strokeB = new Region();
        strokeA.getStyleClass().addAll("brand-stroke", "brand-stroke-a");
        strokeB.getStyleClass().addAll("brand-stroke", "brand-stroke-b");
        mark.getChildren().addAll(strokeA, strokeB);

        StackPane core = new StackPane(plate, mark);
        core.setMouseTransparent(true);
        visual.getChildren().addAll(orbitLayer, core);
        return visual;
    }

    private void playAmbientMotion(Pane ambient, VBox centerpiece) {
        for (Node node : ambient.getChildren()) {
            double[] values = (double[]) node.getProperties().get("motion");
            Timeline drift = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(node.translateXProperty(), values[0], Interpolator.LINEAR),
                            new KeyValue(node.translateYProperty(), values[1], Interpolator.LINEAR),
                            new KeyValue(node.scaleXProperty(), values[4], EASE),
                            new KeyValue(node.scaleYProperty(), values[4], EASE)),
                    new KeyFrame(Duration.millis(values[6]),
                            new KeyValue(node.translateXProperty(), values[2], EASE),
                            new KeyValue(node.translateYProperty(), values[3], EASE),
                            new KeyValue(node.scaleXProperty(), values[5], EASE),
                            new KeyValue(node.scaleYProperty(), values[5], EASE))
            );
            drift.setAutoReverse(true);
            drift.setCycleCount(Animation.INDEFINITE);
            motion.add(drift);
            drift.play();
        }

        centerpiece.setOpacity(0);
        centerpiece.setScaleX(0.94);
        centerpiece.setScaleY(0.94);
        Timeline entrance = new Timeline(new KeyFrame(Duration.millis(520),
                new KeyValue(centerpiece.opacityProperty(), 1, EASE),
                new KeyValue(centerpiece.scaleXProperty(), 1, EASE),
                new KeyValue(centerpiece.scaleYProperty(), 1, EASE)));
        motion.add(entrance);
        entrance.play();
    }

    public void update(String ignoredMessage, double value) {
        targetProgress = clamp(value);
        if (reducedMotion) {
            displayedProgress = targetProgress;
            progress.setProgress(displayedProgress);
        }
    }

    public void prepareApplication(StackPane host, Node application) {
        application.setOpacity(0);
        if (!host.getChildren().contains(application)) host.getChildren().add(0, application);
        toFront();
    }

    public void reveal(StackPane host, Node application, boolean reducedMotion) {
        reveal(host, application, reducedMotion, () -> {
        });
    }

    public void reveal(StackPane host, Node application, boolean reducedMotion, Runnable finished) {
        application.setOpacity(0);
        prepareApplication(host, application);
        update("", 1);
        if (reducedMotion) {
            holdUntilMinimum(() -> finishImmediately(host, application, finished));
            return;
        }

        holdUntilMinimum(() -> {
            if (displayedProgress >= 0.988) playReveal(host, application, finished);
            else progressCompletion = () -> playReveal(host, application, finished);
        });
    }

    private void holdUntilMinimum(Runnable complete) {
        double remainingMillis = Math.max(0,
                (MINIMUM_VISIBLE_NANOS - (System.nanoTime() - createdAt)) / 1_000_000d);
        if (remainingMillis == 0) {
            complete.run();
            return;
        }
        PauseTransition hold = new PauseTransition(Duration.millis(remainingMillis));
        hold.setOnFinished(event -> complete.run());
        motion.add(hold);
        hold.play();
    }

    private void playReveal(StackPane host, Node application, Runnable finished) {
        if (revealStarted) return;
        revealStarted = true;
        FadeTransition appFade = new FadeTransition(Duration.millis(360), application);
        appFade.setToValue(1);
        appFade.setInterpolator(EASE);
        Timeline splashOut = new Timeline(new KeyFrame(Duration.millis(300),
                new KeyValue(opacityProperty(), 0, EASE),
                new KeyValue(content.scaleXProperty(), 0.985, EASE),
                new KeyValue(content.scaleYProperty(), 0.985, EASE)));
        ParallelTransition transition = new ParallelTransition(appFade, splashOut);
        transition.setOnFinished(event -> {
            host.getStyleClass().remove("bootstrap-host");
            host.getChildren().setAll(application);
            close();
            finished.run();
        });
        transition.play();
    }

    private void finishImmediately(StackPane host, Node application, Runnable finished) {
        host.getStyleClass().remove("bootstrap-host");
        host.getChildren().setAll(application);
        application.setOpacity(1);
        close();
        finished.run();
    }

    double progressValue() {
        return progress.getProgress();
    }

    double rotationValue() {
        return rotatingFrame == null ? 0 : rotatingFrame.getRotate();
    }

    public long largestFrameGapMillis() {
        return largestFrameGap / 1_000_000L;
    }

    @Override
    public void close() {
        progressDriver.stop();
        for (Animation animation : motion) animation.stop();
        motion.clear();
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
