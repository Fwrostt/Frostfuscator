package dev.frost.obfuscator.gui.component;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TitledPane;
import javafx.util.Duration;

public final class MenuAnimation {

    private MenuAnimation() {}

    public static void setup(MenuButton menuButton) {
        if (menuButton == null) return;

        menuButton.showingProperty().addListener((obs, oldVal, isShowing) -> {
            animateArrow(menuButton, isShowing ? 90 : 0);
            if (isShowing) {
                Platform.runLater(() -> {
                    if (menuButton.getContextMenu() != null) {
                        setup(menuButton.getContextMenu());
                    }
                });
            }
        });
    }

    public static void setup(ComboBox<?> comboBox) {
        if (comboBox == null) return;

        comboBox.showingProperty().addListener((obs, oldVal, isShowing) -> {
            animateArrow(comboBox, isShowing ? 90 : 0);
        });
    }

    public static void setup(TitledPane titledPane) {
        if (titledPane == null) return;

        titledPane.expandedProperty().addListener((obs, oldVal, isExpanded) -> {
            animateArrow(titledPane, isExpanded ? 90 : 0);
        });
    }

    public static void setup(ContextMenu contextMenu) {
        if (contextMenu == null) return;

        contextMenu.setOnShowing(e -> Platform.runLater(() -> {
            if (contextMenu.getSkin() != null) {
                Node popupNode = contextMenu.getSkin().getNode();
                if (popupNode != null) {
                    animatePopupEntry(popupNode);
                }
            }
        }));
    }

    private static void animateArrow(Node container, double toAngle) {
        Platform.runLater(() -> {
            Node arrow = container.lookup(".arrow");
            if (arrow != null) {
                RotateTransition rt = new RotateTransition(Duration.millis(180), arrow);
                rt.setToAngle(toAngle);
                rt.setInterpolator(Interpolator.EASE_BOTH);
                rt.play();
            }
        });
    }

    private static void animatePopupEntry(Node node) {
        node.setOpacity(0.0);
        node.setScaleY(0.92);
        node.setTranslateY(-5);

        FadeTransition fade = new FadeTransition(Duration.millis(150), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);

        ScaleTransition scale = new ScaleTransition(Duration.millis(150), node);
        scale.setFromY(0.92);
        scale.setToY(1.0);

        TranslateTransition trans = new TranslateTransition(Duration.millis(150), node);
        trans.setFromY(-5);
        trans.setToY(0);

        ParallelTransition anim = new ParallelTransition(fade, scale, trans);
        anim.setInterpolator(Interpolator.EASE_OUT);
        anim.play();
    }
}
