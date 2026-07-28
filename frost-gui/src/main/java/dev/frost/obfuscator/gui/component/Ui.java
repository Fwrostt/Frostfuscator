package dev.frost.obfuscator.gui.component;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class Ui {
    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 8;
    public static final int SPACE_3 = 12;
    public static final int SPACE_4 = 16;
    public static final int SPACE_6 = 24;
    public static final int SPACE_8 = 32;
    public static final int SPACE_12 = 48;

    private Ui() {}

    public static Label label(String text, String... styles) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styles);
        return label;
    }

    public static Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> action.run());
        return button;
    }

    public static VBox pageHeader(String title, String subtitle) {
        Label heading = label(title, "page-title");
        Label description = label(subtitle, "page-subtitle");
        description.setWrapText(true);
        description.setMaxWidth(760);
        return new VBox(SPACE_2, heading, description);
    }

    public static VBox section(String title, String description, Node... content) {
        VBox header = new VBox(SPACE_1, label(title, "section-title"), label(description, "section-description"));
        ((Label) header.getChildren().get(1)).setWrapText(true);
        VBox section = new VBox(SPACE_4);
        section.getStyleClass().add("section");
        section.getChildren().add(header);
        section.getChildren().addAll(content);
        return section;
    }

    public static GridPane fieldRow(String labelText, Node control) {
        Label label = label(labelText, "field-label");
        label.setWrapText(true);
        label.setMinWidth(0);
        if (control instanceof Region region) {
            region.setMinWidth(0);
            region.setMaxWidth(Double.MAX_VALUE);
        }
        GridPane row = new GridPane();
        row.setHgap(SPACE_4);
        row.setVgap(SPACE_2);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        ColumnConstraints key = new ColumnConstraints(100, 150, 170);
        key.setHgrow(Priority.NEVER);
        ColumnConstraints value = new ColumnConstraints();
        value.setMinWidth(0);
        value.setHgrow(Priority.ALWAYS);
        value.setFillWidth(true);
        row.getColumnConstraints().addAll(key, value);
        row.add(label, 0, 0);
        row.add(control, 1, 0);
        GridPane.setHgrow(control, Priority.ALWAYS);
        return row;
    }

    public static ScrollPane pageScroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().add("page-scroll");
        scroll.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        if (content instanceof Region region) {
            region.setMinWidth(0);
            region.setMaxWidth(Double.MAX_VALUE);
            scroll.viewportBoundsProperty().addListener((obs, old, bounds) -> {
                if (bounds.getWidth() > 0) region.setPrefWidth(bounds.getWidth());
            });
        }
        return scroll;
    }

    public static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    public static Insets pageInsets() {
        return new Insets(SPACE_8);
    }
}
