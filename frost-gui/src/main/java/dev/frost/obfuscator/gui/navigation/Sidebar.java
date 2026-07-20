package dev.frost.obfuscator.gui.navigation;

import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import dev.frost.obfuscator.gui.theme.ThemeManager;
import javafx.animation.*;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class Sidebar extends VBox {
    private static final double EXPANDED_WIDTH = 196;
    private static final double COLLAPSED_WIDTH = 64;
    private static final double ICON_SLOT_WIDTH = 48;
    private static final Duration RAIL_DURATION = Duration.millis(360);
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass COLLAPSED = PseudoClass.getPseudoClass("collapsed");
    private static final Interpolator EASE_OUT = Interpolator.SPLINE(0.16, 1, 0.30, 1);

    private final Map<PageId, Button> buttons = new EnumMap<>(PageId.class);
    private final Map<PageId, Label> labels = new EnumMap<>(PageId.class);
    private final Map<Node, Animation> hoverTransitions = new WeakHashMap<>();
    private final BooleanProperty collapsed = new SimpleBooleanProperty();
    private final DoubleProperty animatedWidth = new SimpleDoubleProperty(this, "animatedWidth", EXPANDED_WIDTH);
    private final PreferencesStore preferences;
    private final ThemeManager themes;
    private final Consumer<PageId> navigation;
    private final Label version = new Label(versionLabel());
    private final FontIcon collapseIcon = new FontIcon("fth-chevrons-left");
    private final Button collapseButton = new Button();
    private final StackPane footer;
    private final Rectangle railClip = new Rectangle();
    private Animation railTransition;

    public Sidebar(PreferencesStore preferences, ThemeManager themes, Consumer<PageId> navigation) {
        super(Ui.SPACE_2);
        this.preferences = preferences;
        this.themes = themes;
        this.navigation = navigation;
        getStyleClass().add("sidebar");
        setFillWidth(true);
        setSnapToPixel(true);
        minWidthProperty().bind(animatedWidth);
        prefWidthProperty().bind(animatedWidth);
        maxWidthProperty().bind(animatedWidth);
        collapsed.set(preferences.getBoolean("sidebar.collapsed", false));
        railClip.widthProperty().bind(widthProperty());
        railClip.heightProperty().bind(heightProperty());
        setClip(railClip);

        VBox primary = new VBox(Ui.SPACE_2);
        primary.setFillWidth(true);
        for (PageId page : PageId.values()) {
            if (page == PageId.PRESETS || page == PageId.SETTINGS) continue;
            primary.getChildren().add(createButton(page));
        }
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox secondary = new VBox(Ui.SPACE_2, createButton(PageId.PRESETS), createButton(PageId.SETTINGS));
        secondary.setFillWidth(true);

        collapseIcon.getStyleClass().add("nav-icon");
        collapseButton.setGraphic(collapseIcon);
        collapseButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        collapseButton.setAccessibleText("Collapse sidebar");
        collapseButton.getStyleClass().add("sidebar-collapse");
        collapseButton.setOnAction(event -> collapsed.set(!collapsed.get()));
        footer = new StackPane(version, collapseButton);
        footer.setMinHeight(40);
        footer.setPrefHeight(40);
        footer.setMaxHeight(40);
        StackPane.setAlignment(version, Pos.CENTER_LEFT);
        StackPane.setAlignment(collapseButton, Pos.CENTER);
        collapseButton.translateXProperty().bind(footer.widthProperty().subtract(ICON_SLOT_WIDTH).divide(2));
        footer.getStyleClass().add("sidebar-footer");
        getChildren().addAll(primary, spacer, secondary, footer);

        collapsed.addListener((obs, old, value) -> animateCollapsed(value));
        applyCollapsedInstant(collapsed.get());
    }

    private Button createButton(PageId page) {
        FontIcon icon = new FontIcon(page.iconLiteral());
        icon.setIconSize(18);
        icon.getStyleClass().add("nav-icon");
        StackPane iconSlot = new StackPane(icon);
        iconSlot.getStyleClass().add("nav-icon-slot");
        iconSlot.setMinWidth(ICON_SLOT_WIDTH);
        iconSlot.setPrefWidth(ICON_SLOT_WIDTH);
        iconSlot.setMaxWidth(ICON_SLOT_WIDTH);

        Label text = new Label(page.label());
        text.getStyleClass().add("nav-label");
        labels.put(page, text);

        HBox content = new HBox(iconSlot, text);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setFillHeight(true);
        Button button = new Button();
        button.setGraphic(content);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("sidebar-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinWidth(0);
        button.setAccessibleText(page.label());
        button.setOnAction(event -> navigation.accept(page));
        installHoverMotion(button, icon);
        buttons.put(page, button);
        return button;
    }

    public void select(PageId page) {
        buttons.forEach((id, button) -> {
            boolean selected = id == page;
            button.pseudoClassStateChanged(SELECTED, selected);
        });
    }

    private void installHoverMotion(Button button, Node icon) {
        button.setOnMouseEntered(event -> translate(icon, 3));
        button.setOnMouseExited(event -> translate(icon, 0));
        button.setOnMousePressed(event -> {
            if (!themes.reducedMotionProperty().get()) {
                button.setScaleX(0.985);
                button.setScaleY(0.985);
            }
        });
        button.setOnMouseReleased(event -> {
            button.setScaleX(1);
            button.setScaleY(1);
        });
    }

    private void translate(Node node, double x) {
        Animation previous = hoverTransitions.remove(node);
        if (previous != null) previous.stop();
        if (themes.reducedMotionProperty().get()) {
            node.setTranslateX(0);
            return;
        }
        TranslateTransition transition = new TranslateTransition(Duration.millis(150), node);
        transition.setToX(x);
        transition.setInterpolator(EASE_OUT);
        transition.setOnFinished(event -> hoverTransitions.remove(node));
        hoverTransitions.put(node, transition);
        transition.play();
    }

    private void applyCollapsedInstant(boolean value) {
        double width = value ? COLLAPSED_WIDTH : EXPANDED_WIDTH;
        animatedWidth.set(width);
        pseudoClassStateChanged(COLLAPSED, value);
        labels.values().forEach(label -> {
            label.setOpacity(value ? 0 : 1);
            label.setTranslateX(0);
        });
        version.setOpacity(value ? 0 : 1);
        version.setTranslateX(0);
        collapseIcon.setRotate(value ? 180 : 0);
        collapseButton.setAccessibleText(value ? "Expand sidebar" : "Collapse sidebar");
    }

    private void animateCollapsed(boolean value) {
        preferences.putBoolean("sidebar.collapsed", value);
        if (railTransition != null) railTransition.stop();
        if (themes.reducedMotionProperty().get()) {
            applyCollapsedInstant(value);
            return;
        }

        double startWidth = getWidth() > 0 ? getWidth() : getPrefWidth();
        double targetWidth = value ? COLLAPSED_WIDTH : EXPANDED_WIDTH;
        animatedWidth.set(startWidth);
        pseudoClassStateChanged(COLLAPSED, value);
        collapseButton.setAccessibleText(value ? "Expand sidebar" : "Collapse sidebar");

        List<KeyValue> values = new ArrayList<>();
        values.add(new KeyValue(animatedWidth, targetWidth, EASE_OUT));
        values.add(new KeyValue(version.opacityProperty(), value ? 0 : 1, EASE_OUT));
        values.add(new KeyValue(collapseIcon.rotateProperty(), value ? 180 : 0, EASE_OUT));
        labels.values().forEach(label -> {
            values.add(new KeyValue(label.opacityProperty(), value ? 0 : 1, EASE_OUT));
        });
        Timeline timeline = new Timeline(new KeyFrame(RAIL_DURATION, values.toArray(KeyValue[]::new)));
        railTransition = timeline;
        railTransition.setOnFinished(event -> {
            applyCollapsedInstant(value);
            railTransition = null;
        });
        railTransition.play();
    }

    private static String versionLabel() {
        Properties properties = new Properties();
        try (var stream = Sidebar.class.getResourceAsStream("/frost-version.properties")) {
            if (stream != null) properties.load(stream);
        } catch (Exception ignored) {
        }
        String value = properties.getProperty("version",
                Sidebar.class.getPackage().getImplementationVersion());
        return value == null || value.isBlank() ? "Development" : "v" + value;
    }
}
