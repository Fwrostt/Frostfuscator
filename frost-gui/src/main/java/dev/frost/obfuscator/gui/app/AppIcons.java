package dev.frost.obfuscator.gui.app;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;

/** Central access to the supplied 128 px application and in-app brand icons. */
public final class AppIcons {
    public static final String WINDOW_ICON_RESOURCE = "/icon/FrostfuscatorIcon-128x128.png";
    public static final String TRANSPARENT_ICON_RESOURCE =
            "/icon/FrostfuscatorIcon-Transparent-128x128.png";

    private AppIcons() {
    }

    public static void install(Stage stage) {
        stage.getIcons().setAll(windowIcon());
    }

    public static Image windowIcon() {
        return WindowIconHolder.IMAGE;
    }

    public static Image transparentIcon() {
        return TransparentIconHolder.IMAGE;
    }

    public static ImageView transparentView(double size, String... styleClasses) {
        ImageView view = new ImageView(transparentIcon());
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setCache(true);
        view.setMouseTransparent(true);
        view.setFocusTraversable(false);
        view.getStyleClass().addAll(styleClasses);
        return view;
    }

    private static Image load(String resource) {
        try (InputStream input = AppIcons.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing application icon: " + resource);
            Image image = new Image(input);
            if (image.isError()) throw new IllegalStateException("Invalid application icon: " + resource,
                    image.getException());
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load application icon: " + resource, exception);
        }
    }

    private static final class WindowIconHolder {
        private static final Image IMAGE = load(WINDOW_ICON_RESOURCE);
    }

    private static final class TransparentIconHolder {
        private static final Image IMAGE = load(TRANSPARENT_ICON_RESOURCE);
    }
}
