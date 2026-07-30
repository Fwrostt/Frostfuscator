package dev.frost.obfuscator.gui.app;

import dev.frost.obfuscator.gui.FrostFxApp;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupViewTest extends ApplicationTest {
    private StartupView startup;
    private StackPane host;

    @Override
    public void start(Stage stage) {
        startup = new StartupView(stage, true);
        host = new StackPane(startup);
        host.getStyleClass().add("bootstrap-host");
        Scene scene = new Scene(host, 1180, 760);
        scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void stopAnimations() {
        if (startup != null) startup.close();
    }

    @Test
    void startupIsCenteredMinimalAndCorrectlySized() throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(lookup(".startup-step").queryAll().isEmpty());
        assertTrue(lookup(".startup-title").queryAll().isEmpty());
        assertEquals(1, lookup(".startup-minimal-name").queryAll().size());
        assertEquals(1, lookup(".startup-title-bar").queryAll().size());
        assertEquals(1, lookup(".startup-close-button").queryAll().size());
        assertEquals(3, lookup(".startup-title-bar .window-button").queryAll().size());
        assertEquals(1, Window.getWindows().stream().filter(Window::isShowing).count(),
                "startup must remain inside the primary application window");
        assertEquals(300, lookup(".startup-emblem").query().getBoundsInParent().getWidth(), 1);
        assertEquals(420, lookup(".startup-minimal-progress").query().getBoundsInParent().getWidth(), 1);
        assertEquals(startup.getScene().getWidth(), startup.getWidth(), 1);
        assertEquals(startup.getScene().getHeight(), startup.getHeight(), 1);
        assertTrue(startup.getBackground().getFills().getFirst()
                .getRadii().getTopLeftHorizontalRadius() > 0);
        var contentBounds = lookup(".startup-minimal-content").query().localToScene(
                lookup(".startup-minimal-content").query().getLayoutBounds());
        assertEquals(startup.getScene().getWidth() / 2, contentBounds.getCenterX(), 1);
        assertEquals(startup.getScene().getHeight() / 2, contentBounds.getCenterY(), 1);

        interact(() -> startup.update("", 0.62));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0.62, startup.progressValue(), 0.001);

        AtomicReference<WritableImage> snapshot = new AtomicReference<>();
        interact(() -> snapshot.set(host.snapshot(null, null)));
        saveSnapshot(snapshot.get(), Path.of("build", "reports", "ui", "startup-preview.png"));
    }

    @Test
    void progressInterpolatesInsteadOfJumpingBetweenMilestones() throws TimeoutException {
        AtomicReference<Double> immediate = new AtomicReference<>();
        interact(() -> {
            StackPane host = (StackPane) startup.getParent();
            startup.close();
            startup = new StartupView(false);
            host.getChildren().setAll(startup);
            startup.update("", 0.84);
            immediate.set(startup.progressValue());
        });
        assertTrue(immediate.get() < 0.20);
        WaitForAsyncUtils.waitFor(4, TimeUnit.SECONDS,
                () -> startup.progressValue() > 0.60 && startup.progressValue() <= 0.84);
        double rotationAtProgress = startup.rotationValue();
        WaitForAsyncUtils.waitFor(1, TimeUnit.SECONDS,
                () -> Math.abs(startup.rotationValue() - rotationAtProgress) > 4);
    }

    @Test
    void rotorKeepsTurningWhenProgressIsSettled() throws TimeoutException {
        interact(() -> startup.update("", 0.62));
        double settledProgress = startup.progressValue();
        double initialRotation = startup.rotationValue();
        WaitForAsyncUtils.waitFor(1, TimeUnit.SECONDS,
                () -> Math.abs(startup.rotationValue() - initialRotation) > 4);
        assertEquals(settledProgress, startup.progressValue(), 0.001);
        assertEquals(1, lookup(".startup-rotor-orbit").queryAll().size());
        assertTrue(lookup(".startup-rotor-marker").queryAll().isEmpty());
    }

    @Test
    void windowMaskClipsCornersAndIsRemovedWhenMaximized() {
        StackPane host = new StackPane();
        Rectangle clip = new Rectangle();
        interact(() -> {
            FrostFxApp.applyWindowShape(host, clip, false);
            assertEquals(clip, host.getClip());
            assertEquals(24, clip.getArcWidth());
            assertTrue(!host.getStyleClass().contains("window-maximized"));

            FrostFxApp.applyWindowShape(host, clip, true);
            assertEquals(null, host.getClip());
            assertTrue(host.getStyleClass().contains("window-maximized"));
        });
    }

    private static void saveSnapshot(WritableImage snapshot, Path output) throws Exception {
        BufferedImage image = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(),
                BufferedImage.TYPE_INT_ARGB_PRE);
        var pixels = snapshot.getPixelReader();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, pixels.getArgb(x, y));
        }
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }
}
