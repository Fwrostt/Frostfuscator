package dev.frost.obfuscator.gui.titlebar;

import dev.frost.obfuscator.gui.FrostFxApp;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomTitleBarTest extends ApplicationTest {
    private Stage stage;
    private StackPane host;
    private AppContext context;
    private PreferencesStore preferences;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        preferences = new PreferencesStore(Files.createTempDirectory("frost-window-state-test"));
        context = AppContext.create(stage, preferences);
        host = new StackPane();
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(host.widthProperty());
        clip.heightProperty().bind(host.heightProperty());
        FrostFxApp.applyWindowShape(host, clip, false);
        stage.setScene(new Scene(host, PreferencesStore.DEFAULT_WINDOW_WIDTH,
                PreferencesStore.DEFAULT_WINDOW_HEIGHT));
        stage.show();
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    @Test
    void launchMaximizeRestoresToCompactBoundsAndPersistsThoseNormalBounds() {
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> CustomTitleBar.restoreInitialMaximized(stage, host));
        assertTrue(CustomTitleBar.isCustomMaximized(stage));

        interact(() -> CustomTitleBar.toggleMaximize(context, host));
        assertFalse(CustomTitleBar.isCustomMaximized(stage));
        assertEquals(PreferencesStore.DEFAULT_WINDOW_WIDTH, stage.getWidth(), 1.5);
        assertEquals(PreferencesStore.DEFAULT_WINDOW_HEIGHT, stage.getHeight(), 1.5);

        interact(() -> {
            CustomTitleBar.toggleMaximize(context, host);
            preferences.saveWindow(stage);
            preferences.flush();
        });
        assertTrue(preferences.getBoolean("window.maximized", false));
        assertEquals(PreferencesStore.DEFAULT_WINDOW_WIDTH,
                preferences.getDouble("window.width", 0), 1.5);
        assertEquals(PreferencesStore.DEFAULT_WINDOW_HEIGHT,
                preferences.getDouble("window.height", 0), 1.5);
    }
}
