package dev.frost.obfuscator.gui.notification;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

public final class NotificationCenter {
    private final StackPane overlay = new StackPane();

    public NotificationCenter() {
        overlay.setMouseTransparent(true);
        overlay.setPickOnBounds(false);
        StackPane.setAlignment(overlay, Pos.BOTTOM_CENTER);
    }

    public StackPane overlay() { return overlay; }

    public void show(String message) {
        Label toast = new Label(message);
        toast.getStyleClass().add("toast");
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 24, 0));
        overlay.getChildren().setAll(toast);
        toast.setOpacity(0);
        FadeTransition in = new FadeTransition(Duration.millis(140), toast);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.seconds(2.4));
        FadeTransition out = new FadeTransition(Duration.millis(160), toast);
        out.setToValue(0);
        out.setOnFinished(event -> overlay.getChildren().remove(toast));
        in.setOnFinished(event -> hold.play());
        hold.setOnFinished(event -> out.play());
        in.play();
    }
}
