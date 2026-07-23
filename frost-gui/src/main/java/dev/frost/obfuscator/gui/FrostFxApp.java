package dev.frost.obfuscator.gui;

import atlantafx.base.theme.PrimerDark;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.app.AppShell;
import dev.frost.obfuscator.gui.app.NativeStartupOverlay;
import dev.frost.obfuscator.gui.app.StartupView;
import dev.frost.obfuscator.gui.analysis.ProjectAnalysis;
import dev.frost.obfuscator.gui.console.LogEntry;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX lifecycle entry point. All application dependencies are assembled here
 * and passed into the shell through {@link AppContext}.
 */
public final class FrostFxApp extends Application {
    private AppContext context;
    private NativeStartupOverlay nativeStartup;

    public static void launchApp(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        PreferencesStore preferences = new PreferencesStore();

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("Frostfuscator");
        stage.setMinWidth(1080);
        stage.setMinHeight(700);

        StartupView startup = new StartupView(preferences.getBoolean("ui.reducedMotion", false));
        StackPane host = new StackPane(startup);
        host.getStyleClass().add("bootstrap-host");
        Rectangle windowClip = new Rectangle();
        windowClip.widthProperty().bind(host.widthProperty());
        windowClip.heightProperty().bind(host.heightProperty());
        windowClip.setSmooth(true);
        Scene scene = new Scene(host, 1480, 900);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
        stage.setScene(scene);

        preferences.restoreWindow(stage);
        if (preferences.getBoolean("window.maximized", false)) {
            Platform.runLater(() -> {
                if (context != null) {
                    dev.frost.obfuscator.gui.titlebar.CustomTitleBar.toggleMaximize(context, host);
                } else {
                    javafx.geometry.Rectangle2D vis = javafx.stage.Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()).get(0).getVisualBounds();
                    stage.setX(vis.getMinX());
                    stage.setY(vis.getMinY());
                    stage.setWidth(vis.getWidth());
                    stage.setHeight(vis.getHeight());
                    applyWindowShape(host, windowClip, true);
                }
            });
        } else {
            applyWindowShape(host, windowClip, false);
        }
        stage.show();
        nativeStartup = NativeStartupOverlay.show(
                stage, preferences.getBoolean("ui.reducedMotion", false));
        startup.setProgressMirror(nativeStartup::update);
        Runnable syncStartupBounds = () -> nativeStartup.syncBounds(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        stage.xProperty().addListener((obs, old, value) -> syncStartupBounds.run());
        stage.yProperty().addListener((obs, old, value) -> syncStartupBounds.run());
        stage.widthProperty().addListener((obs, old, value) -> syncStartupBounds.run());
        stage.heightProperty().addListener((obs, old, value) -> syncStartupBounds.run());
        long shownAt = System.nanoTime();

        stage.setOnCloseRequest(event -> {
            preferences.saveWindow(stage);
            if (context != null) context.close();
            else preferences.close();
            startup.close();
            if (nativeStartup != null) nativeStartup.close();
        });

        CompletableFuture.runAsync(this::loadFonts).whenComplete((ignored, fontFailure) ->
                Platform.runLater(() -> initializeWorkspace(
                        stage, scene, host, startup, preferences, shownAt)));
    }

    private void initializeWorkspace(Stage stage, Scene scene, StackPane host, StartupView startup,
                                     PreferencesStore preferences, long shownAt) {
        startup.update("", 0.12);
        context = AppContext.createForStartup(stage, preferences);
        startup.update("", 0.20);
        context.workspacePersistence().restoreAsync().whenComplete((ignored, restoreFailure) ->
                Platform.runLater(() -> {
                    // Restore reusable configuration while transient input,
                    // build, analysis, and console state stays launch-scoped.
                    PauseTransition nextPulse = new PauseTransition(Duration.millis(48));
                    nextPulse.setOnFinished(event -> initializeShell(
                            stage, scene, host, startup, shownAt));
                    nextPulse.play();
                }));
    }

    private void initializeShell(Stage stage, Scene scene, StackPane host,
                                 StartupView startup, long shownAt) {
        long shellStarted = System.nanoTime();
        AppShell shell = new AppShell(context);
        context.themeManager().attach(scene, shell.root());
        applyMaximizedChrome(shell, stage.isMaximized());
        stage.maximizedProperty().addListener((obs, old, maximized) ->
                applyMaximizedChrome(shell, maximized));
        startup.prepareApplication(host, shell.root());
        long shellMillis = (System.nanoTime() - shellStarted) / 1_000_000L;
        startup.update("", 0.30);

        restoreProjectAnalysis().whenComplete((restored, failure) -> Platform.runLater(() -> {
            long validationStarted = System.nanoTime();
            if (restored != null) restored.ifPresent(context.projectState()::setAnalysis);
            context.validationCoordinator().validateNow();
            long validationMillis = (System.nanoTime() - validationStarted) / 1_000_000L;
            startup.update("", 0.38);
            Map<PageId, Long> pageTimes = new EnumMap<>(PageId.class);
            preloadNextSmooth(shell, startup, PageId.values(), 0, pageTimes, () -> {
                long initialPageStarted = System.nanoTime();
                shell.showInitialPage();
                long initialPageMillis = (System.nanoTime() - initialPageStarted) / 1_000_000L;
                startup.update("", 0.96);

                // Give the first page two clean pulses to CSS/layout behind the
                // splash instead of forcing one long synchronous layout pass.
                PauseTransition settle = new PauseTransition(Duration.millis(110));
                settle.setOnFinished(event -> {
                    long totalMillis = (System.nanoTime() - shownAt) / 1_000_000L;
                    long pageMillis = pageTimes.values().stream().mapToLong(Long::longValue).sum();
                    Map.Entry<PageId, Long> slowestPage = pageTimes.entrySet().stream()
                            .max(Map.Entry.comparingByValue()).orElse(null);
                    String slowest = slowestPage == null ? "none"
                            : slowestPage.getKey().name().toLowerCase(java.util.Locale.ROOT)
                                    + " " + slowestPage.getValue() + " ms";
                    context.consoleModel().append(LogEntry.Level.DEBUG,
                            "Startup preloaded " + shell.preloadedPageCount() + " pages in "
                                    + pageMillis + " ms; workspace ready in " + totalMillis
                                    + " ms; slowest page " + slowest + "; longest animation frame "
                                    + startup.largestFrameGapMillis() + " ms; shell " + shellMillis
                                    + " ms; validation " + validationMillis + " ms; initial page "
                                    + initialPageMillis + " ms.");
                    startup.reveal(host, shell.root(),
                            context.themeManager().reducedMotionProperty().get(),
                            nativeStartup == null ? () -> {
                            } : nativeStartup::dismiss);
                });
                settle.play();
            });
        }));
    }

    private void preloadNextSmooth(AppShell shell, StartupView startup, PageId[] pages, int index,
                                   Map<PageId, Long> timings, Runnable complete) {
        if (index >= pages.length) {
            startup.update("", 0.89);
            complete.run();
            return;
        }

        PauseTransition frameBudget = new PauseTransition(Duration.millis(index == 0 ? 52 : 38));
        frameBudget.setOnFinished(event -> {
            PageId page = pages[index];
            timings.put(page, shell.preloadPage(page));
            startup.update("", 0.38 + (0.52 * (index + 1) / pages.length));
            preloadNextSmooth(shell, startup, pages, index + 1, timings, complete);
        });
        frameBudget.play();
    }

    private CompletableFuture<Optional<ProjectAnalysis>> restoreProjectAnalysis() {
        String input = context.projectState().configuration().getInput();
        if (input == null || input.isBlank()) return CompletableFuture.completedFuture(Optional.empty());
        try {
            Path path = Path.of(input);
            if (!Files.isRegularFile(path)) return CompletableFuture.completedFuture(Optional.empty());
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.of(context.jarAnalyzer().analyze(path));
                } catch (Exception ignored) {
                    return Optional.empty();
                }
            });
        } catch (RuntimeException ignored) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private static void applyMaximizedChrome(AppShell shell, boolean maximized) {
        shell.root().getStyleClass().remove("maximized");
        if (maximized) shell.root().getStyleClass().add("maximized");
        shell.setRoundedFrame(!maximized);
    }

    public static void applyWindowShape(StackPane host, Rectangle clip, boolean maximized) {
        if (host != null) {
            host.getStyleClass().removeAll("window-maximized", "maximized");
            if (maximized) {
                host.getStyleClass().addAll("window-maximized", "maximized");
                host.setClip(null);
            } else {
                if (clip != null) {
                    clip.setArcWidth(24);
                    clip.setArcHeight(24);
                    host.setClip(clip);
                }
            }
        }
    }

    private void loadFonts() {
        for (String resource : new String[] {
                "/fonts/JetBrainsMono-Regular.ttf",
                "/fonts/JetBrainsMono-Bold.ttf",
                "/fonts/extras/otf/Inter-Regular.otf",
                "/fonts/extras/otf/Inter-Medium.otf",
                "/fonts/extras/otf/Inter-SemiBold.otf",
                "/fonts/extras/otf/Inter-Bold.otf"
        }) {
            try {
                var url = getClass().getResource(resource);
                if (url != null) {
                    Font.loadFont(url.toExternalForm(), 13.5);
                }
            } catch (Exception ignored) {}
        }
    }
}
