package dev.frost.obfuscator.gui.titlebar;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.Ui;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Map;

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

    private static final String STATE_KEY = CustomTitleBar.class.getName() + ".windowState";

    private static final class WindowState {
        private double normalX;
        private double normalY;
        private double normalWidth;
        private double normalHeight;
        private boolean maximized;
        private Rectangle windowClip;
    }

    public static boolean isCustomMaximized(Stage stage) {
        WindowState state = existingState(stage);
        return state != null && state.maximized;
    }

    public static void toggleMaximize(AppContext context, StackPane host) {
        Stage stage = context.stage();
        WindowState state = state(stage);
        if (!state.maximized) {
            captureNormalBounds(stage, host, state);
            javafx.geometry.Rectangle2D vis = Screen.getScreensForRectangle(
                    state.normalX, state.normalY, state.normalWidth, state.normalHeight)
                    .getFirst().getVisualBounds();
            stage.setX(vis.getMinX());
            stage.setY(vis.getMinY());
            stage.setWidth(vis.getWidth());
            stage.setHeight(vis.getHeight());
            state.maximized = true;
        } else {
            if (Double.isFinite(state.normalX) && Double.isFinite(state.normalY)) {
                stage.setWidth(state.normalWidth);
                stage.setHeight(state.normalHeight);
                stage.setX(state.normalX);
                stage.setY(state.normalY);
            }
            state.maximized = false;
        }

        applyMaximizedState(stage, host, state);
    }

    public static void restoreInitialMaximized(Stage stage, StackPane host) {
        WindowState state = state(stage);
        if (state.maximized) return;
        captureNormalBounds(stage, host, state);
        javafx.geometry.Rectangle2D vis = Screen.getScreensForRectangle(
                state.normalX, state.normalY, state.normalWidth, state.normalHeight)
                .getFirst().getVisualBounds();
        stage.setX(vis.getMinX());
        stage.setY(vis.getMinY());
        stage.setWidth(vis.getWidth());
        stage.setHeight(vis.getHeight());
        state.maximized = true;
        applyMaximizedState(stage, host, state);
    }

    public static javafx.geometry.Rectangle2D normalBounds(Stage stage) {
        WindowState state = existingState(stage);
        if (state == null || !Double.isFinite(state.normalX) || !Double.isFinite(state.normalY)) {
            return null;
        }
        return new javafx.geometry.Rectangle2D(state.normalX, state.normalY,
                state.normalWidth, state.normalHeight);
    }

    private static void captureNormalBounds(Stage stage, StackPane host, WindowState state) {
        state.normalX = stage.getX();
        state.normalY = stage.getY();
        state.normalWidth = stage.getWidth();
        state.normalHeight = stage.getHeight();
        if (host != null && host.getClip() instanceof Rectangle clip) state.windowClip = clip;
    }

    private static void applyMaximizedState(Stage stage, StackPane host, WindowState state) {
        if (host != null) {
            dev.frost.obfuscator.gui.FrostFxApp.applyWindowShape(
                    host, state.windowClip, state.maximized);
        }

        if (stage.getScene() != null && stage.getScene().getRoot() != null) {
            javafx.scene.Node root = stage.getScene().getRoot();
            root.getStyleClass().removeAll("window-maximized", "maximized");
            if (state.maximized) {
                root.getStyleClass().addAll("window-maximized", "maximized");
            }
            for (javafx.scene.Node windowRoot : root.lookupAll(".window-root")) {
                windowRoot.getStyleClass().removeAll("window-maximized", "maximized");
                if (state.maximized) windowRoot.getStyleClass().addAll("window-maximized", "maximized");
            }
        }
    }

    private static WindowState state(Stage stage) {
        Map<Object, Object> properties = stage.getProperties();
        return (WindowState) properties.computeIfAbsent(STATE_KEY, ignored -> new WindowState());
    }

    private static WindowState existingState(Stage stage) {
        return (WindowState) stage.getProperties().get(STATE_KEY);
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
