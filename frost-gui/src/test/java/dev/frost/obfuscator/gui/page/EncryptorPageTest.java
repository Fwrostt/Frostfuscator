package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptorPageTest extends ApplicationTest {
    private AppContext context;
    private StackPane host;
    private Stage stage;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        context = AppContext.create(stage,
                new PreferencesStore(Files.createTempDirectory("frost-encryptor-page-test")));
        host = new StackPane(new EncryptorPage(context).root());
        Scene scene = new Scene(host, 1180, 760);
        scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
        stage.setScene(scene);
        context.themeManager().attach(scene, host);
        stage.show();
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    @Test
    void exposesACompleteResponsiveEncryptionWorkbench() throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(2, lookup(".encryptor-mode-button").queryAll().size());
        assertEquals(4, lookup(".encryptor-security-title").queryAll().size());
        Button run = lookup(".primary-button").queryButton();
        assertEquals("Encrypt file", run.getText());
        assertTrue(run.isDisabled());
        assertFalse(lookup(".encryptor-operation").query().getBoundsInParent().isEmpty());

        ScrollPane scroll = lookup(".page-scroll").query();
        Node page = lookup(".encryptor-page").query();
        Node workbench = lookup(".encryptor-workbench").query();
        assertEquals(scroll.getViewportBounds().getWidth(), page.getLayoutBounds().getWidth(), 1.5);
        assertTrue(workbench.getLayoutBounds().getWidth()
                        >= scroll.getViewportBounds().getWidth() - 65,
                "The workbench should consume the viewport instead of hugging the left edge");

        AtomicReference<WritableImage> snapshot = new AtomicReference<>();
        interact(() -> snapshot.set(host.snapshot(null, null)));
        saveSnapshot(snapshot.get(), Path.of("build-codex-verification", "reports", "ui",
                "encryptor-preview.png"));
    }

    @Test
    void stacksTheSecurityPanelAtCompactViewportWidths() {
        interact(() -> stage.setWidth(820));
        WaitForAsyncUtils.waitForFxEvents();
        Node operation = lookup(".encryptor-operation").query();
        Node security = lookup(".encryptor-security").query();
        var operationBounds = operation.localToScene(operation.getLayoutBounds());
        var securityBounds = security.localToScene(security.getLayoutBounds());
        assertEquals(operationBounds.getMinX(), securityBounds.getMinX(), 1.5);
        assertTrue(securityBounds.getMinY() >= operationBounds.getMaxY() + 23,
                "Compact Encryptor should stack guidance below the form");
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
