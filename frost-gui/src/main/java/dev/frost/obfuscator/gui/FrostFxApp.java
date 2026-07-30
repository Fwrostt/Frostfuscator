package dev.frost.obfuscator.gui;

import atlantafx.base.theme.PrimerDark;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.app.AppShell;
import dev.frost.obfuscator.gui.app.StartupView;
import dev.frost.obfuscator.gui.analysis.ProjectAnalysis;
import dev.frost.obfuscator.gui.console.LogEntry;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX lifecycle entry point. All application dependencies are assembled here
 * and passed into the shell through {@link AppContext}.
 */
public final class FrostFxApp extends Application {
    private AppContext context;
    private volatile boolean closing;

    public static void launchApp(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        PreferencesStore preferences = new PreferencesStore();

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("Frostfuscator");
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        StartupView startup = new StartupView(stage, preferences.getBoolean("ui.reducedMotion", false));
        StackPane host = new StackPane(startup);
        host.getStyleClass().add("bootstrap-host");
        Rectangle windowClip = new Rectangle();
        windowClip.widthProperty().bind(host.widthProperty());
        windowClip.heightProperty().bind(host.heightProperty());
        windowClip.setSmooth(true);
        Scene scene = new Scene(host, PreferencesStore.DEFAULT_WINDOW_WIDTH,
                PreferencesStore.DEFAULT_WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
        stage.setScene(scene);

        applyWindowShape(host, windowClip, false);
        preferences.restoreWindow(stage);
        if (preferences.getBoolean("window.maximized", false)) {
            Platform.runLater(() -> dev.frost.obfuscator.gui.titlebar.CustomTitleBar
                    .restoreInitialMaximized(stage, host));
        }
        long shownAt = System.nanoTime();

        stage.setOnCloseRequest(event -> {
            closing = true;
            preferences.saveWindow(stage);
            if (context != null) context.close();
            else preferences.close();
            startup.close();
        });
        stage.show();

        CompletableFuture.runAsync(this::loadFonts).whenComplete((ignored, fontFailure) ->
                Platform.runLater(() -> {
                    if (!closing) initializeWorkspace(stage, scene, host, startup, preferences, shownAt);
                }));
    }

    private void initializeWorkspace(Stage stage, Scene scene, StackPane host, StartupView startup,
                                     PreferencesStore preferences, long shownAt) {
        if (closing) return;
        startup.update("", 0.12);
        context = AppContext.createForStartup(stage, preferences);
        startup.update("", 0.20);
        context.workspacePersistence().restoreAsync().whenComplete((ignored, restoreFailure) ->
                Platform.runLater(() -> {
                    if (closing) return;
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
        if (closing) return;
        long shellStarted = System.nanoTime();
        AppShell shell = new AppShell(context);
        context.themeManager().attach(scene, shell.root());
        applyMaximizedChrome(shell,
                dev.frost.obfuscator.gui.titlebar.CustomTitleBar.isCustomMaximized(stage));
        stage.maximizedProperty().addListener((obs, old, maximized) ->
                applyMaximizedChrome(shell, maximized));
        startup.prepareApplication(host, shell.root());
        long shellMillis = (System.nanoTime() - shellStarted) / 1_000_000L;
        startup.update("", 0.72);
        long validationStarted = System.nanoTime();
        context.validationCoordinator().validateNow();
        long validationMillis = (System.nanoTime() - validationStarted) / 1_000_000L;
        long initialPageStarted = System.nanoTime();
        shell.showInitialPage();
        long initialPageMillis = (System.nanoTime() - initialPageStarted) / 1_000_000L;
        startup.update("", 0.96);

        PauseTransition settle = new PauseTransition(Duration.millis(90));
        settle.setOnFinished(event -> {
            if (closing) return;
            long totalMillis = (System.nanoTime() - shownAt) / 1_000_000L;
            context.consoleModel().append(LogEntry.Level.DEBUG,
                    "Interactive shell ready in " + totalMillis + " ms; longest startup frame "
                            + startup.largestFrameGapMillis() + " ms; shell " + shellMillis
                            + " ms; validation " + validationMillis + " ms; initial page "
                            + initialPageMillis + " ms.");
            startup.reveal(host, shell.root(), context.themeManager().reducedMotionProperty().get(),
                    this::restoreProjectAnalysisAfterReveal);
        });
        settle.play();
    }

    private void restoreProjectAnalysisAfterReveal() {
        if (closing) return;
        restoreProjectAnalysis().whenComplete((restored, failure) -> Platform.runLater(() -> {
            if (closing || context == null) return;
            if (restored != null) restored.ifPresent(context.projectState()::setAnalysis);
            context.validationCoordinator().validateNow();
        }));
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
