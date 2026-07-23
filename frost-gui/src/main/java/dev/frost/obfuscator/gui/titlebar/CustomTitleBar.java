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
        maximize.setOnAction(event -> toggleMaximize(context, hostNode(this)));

        Button close = windowButton("fth-x", "Close");
        close.getStyleClass().add("window-close");
        close.setOnAction(event -> context.stage().close());
        getChildren().addAll(mark, title, project, dirty, spacer, load, save, minimize, maximize, close);

        setOnMousePressed(event -> {
            dragX = event.getSceneX();
            dragY = event.getSceneY();
        });
        setOnMouseDragged(event -> {
            if (isCustomMaximized(context.stage())) {
                toggleMaximize(context, hostNode(this));
            }
            context.stage().setX(event.getScreenX() - dragX);
            context.stage().setY(event.getScreenY() - dragY);
        });
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                toggleMaximize(context, hostNode(this));
            }
        });
    }

    private static StackPane hostNode(CustomTitleBar bar) {
        if (bar.getScene() != null && bar.getScene().getRoot() instanceof StackPane host) {
            return host;
        }
        return null;
    }

    private static double normalX = Double.NaN;
    private static double normalY = Double.NaN;
    private static double normalW = 1480;
    private static double normalH = 900;
    private static boolean customMaximized = false;

    public static boolean isCustomMaximized(javafx.stage.Stage stage) {
        return customMaximized;
    }

    public static void toggleMaximize(AppContext context, StackPane host) {
        javafx.stage.Stage stage = context.stage();
        if (!customMaximized) {
            normalX = stage.getX();
            normalY = stage.getY();
            normalW = stage.getWidth();
            normalH = stage.getHeight();

            javafx.geometry.Rectangle2D vis = javafx.stage.Screen.getScreensForRectangle(normalX, normalY, normalW, normalH).get(0).getVisualBounds();
            stage.setX(vis.getMinX());
            stage.setY(vis.getMinY());
            stage.setWidth(vis.getWidth());
            stage.setHeight(vis.getHeight());
            customMaximized = true;
        } else {
            if (Double.isFinite(normalX) && Double.isFinite(normalY)) {
                stage.setX(normalX);
                stage.setY(normalY);
                stage.setWidth(normalW);
                stage.setHeight(normalH);
            }
            customMaximized = false;
        }

        if (stage.getScene() != null && stage.getScene().getRoot() != null) {
            javafx.scene.Node root = stage.getScene().getRoot();
            root.getStyleClass().removeAll("window-maximized", "maximized");
            if (customMaximized) {
                root.getStyleClass().addAll("window-maximized", "maximized");
            }
        }

        if (host != null) {
            dev.frost.obfuscator.gui.FrostFxApp.applyWindowShape(host, barClip(host), customMaximized);
        }
    }

    private static javafx.scene.shape.Rectangle barClip(StackPane host) {
        if (host.getClip() instanceof javafx.scene.shape.Rectangle r) return r;
        return null;
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
