package dev.frost.obfuscator.gui.app;

import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.console.LogEntry;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.gui.protection.TransformerCatalog;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.javafx.FontIcon;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AppShellTest extends ApplicationTest {
    private AppContext context;
    private AppShell shell;

    @Override
    public void start(Stage stage) throws Exception {
        PreferencesStore preferences = new PreferencesStore(
                Files.createTempDirectory("frostfuscator-app-shell-test"));
        preferences.putBoolean("sidebar.collapsed", false);
        context = AppContext.create(stage, preferences);
        shell = new AppShell(context);
        Scene scene = new Scene(shell.root(), 1180, 760);
        scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
        stage.setScene(scene);
        context.themeManager().attach(scene, shell.root());
        shell.navigate(PageId.OVERVIEW);
        stage.show();
    }

    @Test
    void everyPageCanBeConstructedAndWarmedBeforeNavigation() {
        interact(() -> {
            for (PageId page : PageId.values()) shell.preload(page);
            shell.showInitialPage();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(PageId.values().length, shell.preloadedPageCount());
        assertNotNull(lookup(".overview-page").query());
    }

    @Test
    void everyPageCanBeConstructedWithoutRepeatedSceneLayout() {
        interact(() -> {
            for (PageId page : PageId.values()) shell.preloadPage(page);
        });
        assertEquals(PageId.values().length, shell.preloadedPageCount());
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    @Test
    void overviewKeepsChromeAndOwnsVerticalScrolling() {
        WaitForAsyncUtils.waitForFxEvents();
        assertNotNull(lookup(".title-bar").query());
        assertTrue(lookup(".page-scroll").query() instanceof ScrollPane);
        assertNotNull(lookup(".overview-page").query());
        Node frame = lookup(".window-frame").query();
        assertNotNull(frame.getClip());
        assertTrue(((javafx.scene.shape.Rectangle) frame.getClip()).getArcWidth() > 0);
    }

    @Test
    void analyticsPageExposesSearchableCompleteInventories() {
        interact(() -> shell.navigate(PageId.REPORTS));
        WaitForAsyncUtils.waitForFxEvents();
        assertNotNull(lookup(".analytics-page").query());
        assertEquals(4, lookup(".analytics-table").queryAll().size());
        assertEquals(3, lookup(".analytics-search").queryAll().size());
        assertNotNull(lookup("Apply recommended setup").queryButton());
        assertNotNull(lookup("Post-build effectiveness").query());
    }

    @Test
    void sidebarUsesVectorIconsAndComboShowsInitialValue() {
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(lookup(".nav-icon").queryAll().isEmpty());
        assertTrue(lookup(".nav-icon").queryAll().stream().anyMatch(FontIcon.class::isInstance));

        AtomicReference<CustomComboBox<String>> combo = new AtomicReference<>();
        interact(() -> combo.set(new CustomComboBox<>(List.of("Balanced", "Strong"))));
        assertEquals("Balanced", combo.get().getText());
    }

    @Test
    void settingsSliderTrackCannotEscapeItsControl() {
        interact(() -> shell.navigate(PageId.SETTINGS));
        WaitForAsyncUtils.waitForFxEvents();
        Node slider = lookup(".smooth-slider").query();
        Node track = lookup(".smooth-slider-track").query();
        assertTrue(track.getBoundsInParent().getMinX() >= 0);
        assertTrue(track.getBoundsInParent().getMaxX() <= slider.getLayoutBounds().getWidth() + 0.5);
    }

    @Test
    void sidebarHasOnlyTwoStableWidths() throws TimeoutException {
        WaitForAsyncUtils.waitForFxEvents();
        Node sidebar = lookup(".sidebar").query();
        assertEquals(196, sidebar.getBoundsInParent().getWidth(), 0.5);
        Node collapse = lookup(".sidebar-collapse").query();
        interact(((Button) collapse)::fire);
        WaitForAsyncUtils.waitFor(1, TimeUnit.SECONDS,
                () -> Math.abs(sidebar.getBoundsInParent().getWidth() - 64) < 0.5);
        assertEquals(64, sidebar.getBoundsInParent().getWidth(), 0.5);
        interact(((Button) collapse)::fire);
        WaitForAsyncUtils.waitFor(1, TimeUnit.SECONDS,
                () -> Math.abs(sidebar.getBoundsInParent().getWidth() - 196) < 0.5);
    }

    @Test
    void problemStatusAndQuickFixNeverCollapseIntoEllipses() {
        interact(() -> {
            context.projectState().problems().setAll(new dev.frost.obfuscator.gui.validation.Problem(
                    dev.frost.obfuscator.gui.validation.Problem.Severity.WARNING,
                    "test-warning",
                    "Reflection-sensitive classes may be renamed",
                    "Runtime name lookup was detected.",
                    "Add keep rules",
                    state -> {
                    }));
            shell.navigate(PageId.OVERVIEW);
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Warning", ((javafx.scene.control.Label) lookup(".status-warning").query()).getText());
        assertNotNull(lookup("Add keep rules").queryButton());
    }

    @Test
    void consoleRendersShortBuildMessages() {
        interact(() -> {
            context.consoleModel().clear();
            context.consoleModel().append(LogEntry.Level.INFO, "Build started.");
            context.consoleModel().append("[INFO] Running transformer: class-rename");
            shell.navigate(PageId.CONSOLE);
        });
        WaitForAsyncUtils.waitForFxEvents();
        @SuppressWarnings("unchecked")
        ListView<LogEntry> output = lookup(".console-list").query();
        assertTrue(output.getItems().stream().anyMatch(entry -> entry.message().contains("Build started.")));
        assertTrue(output.getItems().stream()
                .anyMatch(entry -> entry.message().contains("Running transformer: class-rename")));
    }

    @Test
    void repeatedNavigationToEnteringPageDoesNotDuplicateCachedNode() throws TimeoutException {
        assertDoesNotThrow(() -> interact(() -> {
            shell.navigate(PageId.BUILD);
            shell.navigate(PageId.CONSOLE);
            shell.navigate(PageId.CONSOLE);
        }));
        StackPane host = lookup(".content-host").query();
        WaitForAsyncUtils.waitFor(1, TimeUnit.SECONDS, () -> host.getChildren().size() == 1);
        assertTrue(host.getChildren().getFirst().getStyleClass().contains("console-page"));
    }

    @Test
    void protectionBrowserIsSpaciousAndUsesWrappedCopy() {
        interact(() -> shell.navigate(PageId.PROTECTION));
        WaitForAsyncUtils.waitForFxEvents();
        Region browser = lookup(".transformer-browser").query();
        assertTrue(browser.getWidth() >= 320);
        for (Node node : lookup(".transformer-row-title").queryAll()) {
            Label title = (Label) node;
            assertTrue(title.isWrapText());
            assertEquals(OverrunStyle.CLIP, title.getTextOverrun());
        }
        for (Node node : lookup(".transformer-row-description").queryAll()) {
            Label description = (Label) node;
            assertTrue(description.isWrapText());
            assertTrue(description.getHeight() + 0.5 >= description.prefHeight(description.getWidth()),
                    () -> description.getText() + " was vertically clipped");
        }
        var presetButtons = lookup(".profile-presets .button").queryAll();
        assertFalse(presetButtons.isEmpty());
        for (Node node : presetButtons) {
            Button button = (Button) node;
            assertTrue(button.getWidth() + 0.5 >= button.prefWidth(-1),
                    () -> button.getText() + " was compressed");
        }

        Node detailHeader = lookup(".transformer-detail-header").query();
        Node profileSection = lookup(".profile-section").query();
        var profileBounds = profileSection.localToScene(profileSection.getLayoutBounds());
        var detailBounds = detailHeader.localToScene(detailHeader.getLayoutBounds());
        assertTrue(profileBounds.getMaxY() + 24 <= detailBounds.getMinY(),
                "Project profiles must appear above the transformer heading with clear separation");
        Label enabledPasses = lookup(".enabled-pass-value").query();
        assertTrue(enabledPasses.getText().matches("\\d+ of \\d+ passes"));
        assertTrue(enabledPasses.getAccessibleText().contains("protection passes enabled"));

        @SuppressWarnings("unchecked")
        ListView<TransformerCatalog.Descriptor> list = lookup(".transformer-list").query();
        @SuppressWarnings("unchecked")
        ListCell<TransformerCatalog.Descriptor> selectedCell =
                (ListCell<TransformerCatalog.Descriptor>) list.lookup(".list-cell:selected");
        assertNotNull(selectedCell);
        assertFalse(selectedCell.getBorder().isEmpty());
        assertTrue(selectedCell.getBorder().getStrokes().getFirst().getInsets().getLeft() >= 3,
                "Selected transformer border must be inset so its rounded corners are not clipped");
        assertTrue(selectedCell.getBorder().getStrokes().getFirst().getInsets().getRight() >= 20,
                "Selected transformer border must clear the vertical scrollbar");

        @SuppressWarnings("unchecked")
        CustomComboBox<TransformerCatalog.Category> categoryCombo =
                lookup(".protection-category-combo").query();
        interact(categoryCombo::show);
        WaitForAsyncUtils.waitForFxEvents();
        Node comboLabel = categoryCombo.lookup(".custom-combo-value");
        assertNotNull(comboLabel);
        assertEquals("Renaming", ((Label) comboLabel).getText());
        assertTrue(comboLabel.isVisible());
        assertEquals(1, comboLabel.getOpacity(), 0.01,
                "The selected category must remain visible while the menu is open");
        interact(categoryCombo::hide);

        Node impact = lookup(".impact-panel").query();
        assertTrue(impact.isVisible(), "Guidance panel must remain accessible");
    }

    @Test
    void buildActionsWrapWithoutClippingAtMinimumWorkspaceWidth() {
        interact(() -> {
            shell.navigate(PageId.BUILD);
            context.projectState().busyProperty().set(true);
        });
        WaitForAsyncUtils.waitForFxEvents();
        double sceneWidth = shell.root().getScene().getWidth();
        for (String text : List.of("Choose output", "Build protected JAR", "Cancel build")) {
            Button button = lookup(text).queryButton();
            var bounds = button.localToScene(button.getLayoutBounds());
            assertTrue(bounds.getMaxX() <= sceneWidth + 0.5, () -> text + " escaped the viewport");
            assertTrue(button.getWidth() + 0.5 >= button.prefWidth(-1), () -> text + " was compressed");
        }
    }
}
