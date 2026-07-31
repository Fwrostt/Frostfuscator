package dev.frost.obfuscator.gui.titlebar;

import dev.frost.obfuscator.gui.app.AppIcons;
import dev.frost.obfuscator.gui.component.Ui;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

/** Window controls that remain available while the application context loads. */
public final class StartupTitleBar extends HBox {
    private double dragX;
    private double dragY;

    public StartupTitleBar(Stage stage) {
        super(Ui.SPACE_3);
        getStyleClass().addAll("title-bar", "startup-title-bar");
        setAlignment(Pos.CENTER_LEFT);

        ImageView mark = AppIcons.transparentView(24, "brand-mark", "titlebar-brand-icon");

        Label title = Ui.label("Frostfuscator", "titlebar-name");
        Label status = Ui.label("Starting…", "titlebar-project", "startup-titlebar-status");
        Button minimize = windowButton("fth-minus", "Minimize");
        minimize.setOnAction(event -> stage.setIconified(true));
        Button maximize = windowButton("fth-square", "Maximize or restore");
        maximize.setOnAction(event -> CustomTitleBar.toggleMaximize(stage, hostNode()));
        Button close = windowButton("fth-x", "Close");
        close.getStyleClass().addAll("window-close", "startup-close-button");
        close.setOnAction(event -> stage.close());
        getChildren().addAll(mark, title, status, Ui.spacer(), minimize, maximize, close);

        setOnMousePressed(event -> {
            dragX = event.getSceneX();
            dragY = event.getSceneY();
        });
        setOnMouseDragged(event -> {
            if (CustomTitleBar.isCustomMaximized(stage)) {
                CustomTitleBar.toggleMaximize(stage, hostNode());
            }
            stage.setX(event.getScreenX() - dragX);
            stage.setY(event.getScreenY() - dragY);
        });
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) CustomTitleBar.toggleMaximize(stage, hostNode());
        });
    }

    private StackPane hostNode() {
        return getScene() != null && getScene().getRoot() instanceof StackPane host ? host : null;
    }

    private static Button windowButton(String iconLiteral, String accessibleText) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(15);
        icon.getStyleClass().add("window-icon");
        Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().add("window-button");
        button.setAccessibleText(accessibleText);
        return button;
    }
}
