package dev.frost.obfuscator.gui.titlebar;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.Ui;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

public final class CustomTitleBar extends HBox {
    private double dragX;
    private double dragY;

    public CustomTitleBar(AppContext context, Runnable loadConfig, Runnable saveConfig) {
        super(Ui.SPACE_3);
        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);

        StackPane mark = new StackPane();
        mark.getStyleClass().add("brand-mark");
        Region strokeA = new Region();
        Region strokeB = new Region();
        strokeA.getStyleClass().addAll("brand-stroke", "brand-stroke-a");
        strokeB.getStyleClass().addAll("brand-stroke", "brand-stroke-b");
        mark.getChildren().addAll(strokeA, strokeB);

        Label title = Ui.label("Frostfuscator", "titlebar-name");
        Label project = Ui.label("No project selected", "titlebar-project");
        context.projectState().analysisProperty().addListener((obs, old, value) ->
                project.setText(value.jar() == null ? "No project selected" : value.jar().getFileName().toString()));
        Label dirty = Ui.label("• Unsaved", "titlebar-unsaved");
        dirty.visibleProperty().bind(context.projectState().dirtyProperty());
        dirty.managedProperty().bind(dirty.visibleProperty());

        Button load = Ui.button("Load Config", "titlebar-action", loadConfig);
        Button save = Ui.button("Save Config", "titlebar-action", saveConfig);
        Region spacer = Ui.spacer();
        Button minimize = windowButton("fth-minus", "Minimize");
        minimize.setOnAction(event -> context.stage().setIconified(true));
        Button maximize = windowButton("fth-square", "Maximize or restore");
        maximize.setOnAction(event -> context.stage().setMaximized(!context.stage().isMaximized()));
        Button close = windowButton("fth-x", "Close");
        close.getStyleClass().add("window-close");
        close.setOnAction(event -> context.stage().close());
        getChildren().addAll(mark, title, project, dirty, spacer, load, save, minimize, maximize, close);

        setOnMousePressed(event -> {
            dragX = event.getScreenX() - context.stage().getX();
            dragY = event.getScreenY() - context.stage().getY();
        });
        setOnMouseDragged(event -> {
            if (!context.stage().isMaximized()) {
                context.stage().setX(event.getScreenX() - dragX);
                context.stage().setY(event.getScreenY() - dragY);
            }
        });
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) context.stage().setMaximized(!context.stage().isMaximized());
        });
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
