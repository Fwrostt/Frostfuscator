package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import dev.frost.obfuscator.gui.theme.ThemeManager;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableDensityTest extends ApplicationTest {
    private AppContext context;
    private ReportsPage reportsPage;
    private ProtectionPage protectionPage;
    private StackPane host;

    @Override
    public void start(Stage stage) throws Exception {
        context = AppContext.create(stage,
                new PreferencesStore(Files.createTempDirectory("frost-table-density-test")));
        reportsPage = new ReportsPage(context);
        protectionPage = new ProtectionPage(context);
        host = new StackPane(reportsPage.root(), protectionPage.root());
        Scene scene = new Scene(host, 1280, 800);
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
    void dynamicallyUpdatesTableFixedCellSizeAcrossDensityModes() {
        WaitForAsyncUtils.waitForFxEvents();
        TableView<?> table = lookup(".analytics-table").query();

        // Default density is COMFORTABLE (fixed cell size 38.0)
        assertEquals(38.0, table.getFixedCellSize(), 0.1);

        // Switch density to COMPACT
        interact(() -> context.themeManager().densityProperty().set(ThemeManager.Density.COMPACT));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(28.0, table.getFixedCellSize(), 0.1, "COMPACT mode should reduce row height to 28.0");
        assertTrue(host.getStyleClass().contains("density-compact"));

        // Switch density to SPACIOUS
        interact(() -> context.themeManager().densityProperty().set(ThemeManager.Density.SPACIOUS));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(46.0, table.getFixedCellSize(), 0.1, "SPACIOUS mode should increase row height to 46.0");
        assertTrue(host.getStyleClass().contains("density-spacious"));

        // Return to COMFORTABLE
        interact(() -> context.themeManager().densityProperty().set(ThemeManager.Density.COMFORTABLE));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(38.0, table.getFixedCellSize(), 0.1);
        assertTrue(host.getStyleClass().contains("density-comfortable"));
    }
}
