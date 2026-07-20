package dev.frost.obfuscator.gui;

import atlantafx.base.theme.PrimerDark;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.app.AppShell;
import dev.frost.obfuscator.gui.app.StartupView;
import dev.frost.obfuscator.gui.analysis.ProjectAnalysis;
import dev.frost.obfuscator.gui.console.LogEntry;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

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

        StartupView startup = new StartupView();
        StackPane host = new StackPane(startup);
        host.getStyleClass().add("bootstrap-host");
        Scene scene = new Scene(host, 1480, 900);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
        stage.setScene(scene);

        preferences.restoreWindow(stage);
        stage.show();
        long shownAt = System.nanoTime();

        stage.setOnCloseRequest(event -> {
            preferences.saveWindow(stage);
            if (context != null) context.close();
            else preferences.close();
            startup.close();
        });

        CompletableFuture.runAsync(this::loadFonts).whenComplete((ignored, fontFailure) ->
                Platform.runLater(() -> initializeWorkspace(
                        stage, scene, host, startup, preferences, shownAt)));
    }

    private void initializeWorkspace(Stage stage, Scene scene, StackPane host, StartupView startup,
                                     PreferencesStore preferences, long shownAt) {
        startup.update("Restoring workspace", 0.10);
        context = AppContext.create(stage, preferences);
        AppShell shell = new AppShell(context);
        context.themeManager().attach(scene, shell.root());
        applyMaximizedChrome(shell, stage.isMaximized());
        stage.maximizedProperty().addListener((obs, old, maximized) ->
                applyMaximizedChrome(shell, maximized));
        startup.prepareApplication(host, shell.root());

        CompletableFuture<Optional<ProjectAnalysis>> analysis = restoreProjectAnalysis();
        Map<PageId, Long> pageTimes = new EnumMap<>(PageId.class);
        preloadNext(shell, startup, PageId.values(), 0, pageTimes, () ->
                analysis.whenComplete((restored, failure) -> Platform.runLater(() -> {
                    if (restored != null) restored.ifPresent(context.projectState()::setAnalysis);
                    shell.showInitialPage();
                    context.validationCoordinator().validateNow();
                    host.applyCss();
                    host.layout();
                    long totalMillis = (System.nanoTime() - shownAt) / 1_000_000L;
                    long pageMillis = pageTimes.values().stream().mapToLong(Long::longValue).sum();
                    context.consoleModel().append(LogEntry.Level.DEBUG,
                            "Startup prepared " + shell.preloadedPageCount() + " pages in "
                                    + pageMillis + " ms; workspace ready in " + totalMillis + " ms.");
                    startup.reveal(host, shell.root(),
                            context.themeManager().reducedMotionProperty().get());
                })));
    }

    private void preloadNext(AppShell shell, StartupView startup, PageId[] pages, int index,
                             Map<PageId, Long> timings, Runnable complete) {
        if (index >= pages.length) {
            startup.update("Finishing workspace", 0.91);
            complete.run();
            return;
        }
        PageId page = pages[index];
        double progress = 0.18 + (0.68 * index / pages.length);
        startup.update("Preparing " + page.label(), progress);
        Platform.runLater(() -> {
            timings.put(page, shell.preload(page));
            preloadNext(shell, startup, pages, index + 1, timings, complete);
        });
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

    private void loadFonts() {
        for (String resource : new String[] {
                "/fonts/extras/otf/Inter-Regular.otf",
                "/fonts/extras/otf/Inter-Medium.otf",
                "/fonts/extras/otf/Inter-SemiBold.otf",
                "/fonts/extras/otf/Inter-Bold.otf"
        }) {
            Font.loadFont(getClass().getResource(resource).toExternalForm(), 13.5);
        }
    }
}
