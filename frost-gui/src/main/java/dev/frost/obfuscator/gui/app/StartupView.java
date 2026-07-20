package dev.frost.obfuscator.gui.app;

import dev.frost.obfuscator.gui.component.Ui;
import javafx.animation.*;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class StartupView extends StackPane implements AutoCloseable {
    private final Timeline pulse;
    private final Label status;
    private final ProgressBar progress;

    public StartupView() {
        getStyleClass().add("startup-view");

        StackPane mark = new StackPane();
        mark.getStyleClass().addAll("brand-mark", "startup-mark");
        Region strokeA = new Region();
        Region strokeB = new Region();
        strokeA.getStyleClass().addAll("brand-stroke", "brand-stroke-a");
        strokeB.getStyleClass().addAll("brand-stroke", "brand-stroke-b");
        mark.getChildren().addAll(strokeA, strokeB);

        Label name = Ui.label("Frostfuscator", "startup-name");
        status = Ui.label("Preparing workspace", "startup-status");
        progress = new ProgressBar(0.04);
        progress.setPrefWidth(180);
        progress.getStyleClass().add("startup-progress");
        VBox content = new VBox(Ui.SPACE_3, mark, name, status, progress);
        content.setAlignment(Pos.CENTER);
        getChildren().add(content);

        Interpolator ease = Interpolator.SPLINE(0.22, 1, 0.36, 1);
        pulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(mark.translateYProperty(), 0, ease),
                        new KeyValue(mark.opacityProperty(), 0.72, ease)),
                new KeyFrame(Duration.millis(680),
                        new KeyValue(mark.translateYProperty(), -4, ease),
                        new KeyValue(mark.opacityProperty(), 1, ease))
        );
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
    }

    public void update(String message, double value) {
        status.setText(message);
        progress.setProgress(Math.max(0, Math.min(1, value)));
    }

    public void prepareApplication(StackPane host, javafx.scene.Node application) {
        application.setOpacity(0);
        if (!host.getChildren().contains(application)) host.getChildren().add(0, application);
        toFront();
    }

    public void reveal(StackPane host, javafx.scene.Node application, boolean reducedMotion) {
        application.setOpacity(0);
        prepareApplication(host, application);
        update("Workspace ready", 1);
        if (reducedMotion) {
            host.getStyleClass().remove("bootstrap-host");
            host.getChildren().setAll(application);
            application.setOpacity(1);
            close();
            return;
        }
        Interpolator ease = Interpolator.SPLINE(0.22, 1, 0.36, 1);
        FadeTransition appFade = new FadeTransition(Duration.millis(180), application);
        appFade.setToValue(1);
        appFade.setInterpolator(ease);
        FadeTransition splashFade = new FadeTransition(Duration.millis(150), this);
        splashFade.setToValue(0);
        splashFade.setInterpolator(ease);
        ParallelTransition transition = new ParallelTransition(appFade, splashFade);
        transition.setOnFinished(event -> {
            host.getStyleClass().remove("bootstrap-host");
            host.getChildren().setAll(application);
            close();
        });
        transition.play();
    }

    @Override
    public void close() {
        pulse.stop();
    }
}
