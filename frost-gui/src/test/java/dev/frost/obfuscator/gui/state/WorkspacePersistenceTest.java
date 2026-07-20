package dev.frost.obfuscator.gui.state;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.console.LogEntry;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkspacePersistenceTest extends ApplicationTest {
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    @Test
    void restoresReusableConfigurationButNotProjectOrConsoleState() throws Exception {
        Path root = Files.createTempDirectory("frostfuscator-workspace-test");
        interact(() -> {
            AppContext context = AppContext.create(stage, new PreferencesStore(root));
            context.projectState().configuration().setInput("D:\\work\\input.jar");
            context.projectState().configuration().setOutput("D:\\work\\protected.jar");
            context.projectState().configuration().setLibs("D:\\work\\dependencies");
            context.projectState().configuration().getLibraries()
                    .setPaths(new java.util.ArrayList<>(java.util.List.of("D:\\work\\library.jar")));
            context.projectState().configuration().setDictionary("unicode");
            context.projectState().configuration().getTransformerConfig("anti-debug").setEnabled(true);
            context.projectState().profileProperty().set("Strong");
            context.projectState().goalProperty().set("Strongest protection");
            context.projectState().outputSizeLimitMbProperty().set(42);
            context.projectState().touch();
            context.consoleModel().append(LogEntry.Level.INFO, "Persist this console line");
            context.close();
        });

        assertTrue(Files.isRegularFile(root.resolve("workspace").resolve("session.yml")));
        assertFalse(Files.exists(root.resolve("logs").resolve("latest-session.properties")));
        assertFalse(Files.readString(root.resolve("workspace").resolve("session.yml")).contains("input.jar"));

        AtomicReference<AppContext> restored = new AtomicReference<>();
        interact(() -> restored.set(AppContext.create(stage, new PreferencesStore(root))));
        AppContext context = restored.get();
        try {
            assertTrue(context.projectState().configuration().getInput().isBlank());
            assertTrue(context.projectState().configuration().getOutput().isBlank());
            assertTrue(context.projectState().configuration().getLibs().isBlank());
            assertTrue(context.projectState().configuration().getLibraries().getPaths().isEmpty());
            assertEquals("unicode", context.projectState().configuration().getDictionary());
            assertTrue(context.projectState().configuration().getTransformerConfig("anti-debug").isEnabled());
            assertEquals("Strong", context.projectState().profileProperty().get());
            assertEquals("Strongest protection", context.projectState().goalProperty().get());
            assertEquals(42, context.projectState().outputSizeLimitMbProperty().get(), 0.001);
            assertTrue(context.consoleModel().entries().isEmpty());
            assertFalse(context.projectState().dirtyProperty().get());
        } finally {
            interact(context::close);
        }
    }

    @Test
    void startupRestoreParsesSessionOffThreadAndAppliesItOnFxThread() throws Exception {
        Path root = Files.createTempDirectory("frostfuscator-async-workspace-test");
        interact(() -> {
            AppContext context = AppContext.create(stage, new PreferencesStore(root));
            context.projectState().profileProperty().set("Strong");
            context.projectState().configuration().getTransformerConfig("watermark").setEnabled(false);
            context.projectState().configuration().setInput("D:\\work\\async-input.jar");
            context.projectState().touch();
            context.consoleModel().append(LogEntry.Level.SUCCESS, "Restored asynchronously");
            context.close();
        });

        AtomicReference<AppContext> restored = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> restore = new AtomicReference<>();
        interact(() -> {
            AppContext context = AppContext.createForStartup(stage, new PreferencesStore(root));
            restored.set(context);
            restore.set(context.workspacePersistence().restoreAsync());
        });
        restore.get().get(5, TimeUnit.SECONDS);

        AppContext context = restored.get();
        try {
            assertEquals("Strong", context.projectState().profileProperty().get());
            assertFalse(context.projectState().configuration().getTransformerConfig("watermark").isEnabled());
            assertTrue(context.projectState().configuration().getInput().isBlank());
            assertTrue(context.consoleModel().entries().isEmpty());
        } finally {
            interact(context::close);
        }
    }

    @Test
    void restoreRemovesLegacyBuildAndConsoleFiles() throws Exception {
        Path root = Files.createTempDirectory("frostfuscator-legacy-activity-test");
        Files.createDirectories(root.resolve("history"));
        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("history").resolve("build-history.properties"), "count=1");
        Files.writeString(root.resolve("logs").resolve("latest-session.properties"), "count=1");

        AtomicReference<AppContext> restored = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> restore = new AtomicReference<>();
        interact(() -> {
            AppContext context = AppContext.createForStartup(stage, new PreferencesStore(root));
            restored.set(context);
            restore.set(context.workspacePersistence().restoreAsync());
        });
        restore.get().get(5, TimeUnit.SECONDS);

        AppContext context = restored.get();
        try {
            assertTrue(context.projectState().buildHistory().isEmpty());
            assertTrue(context.consoleModel().entries().isEmpty());
            assertFalse(Files.exists(root.resolve("history").resolve("build-history.properties")));
            assertFalse(Files.exists(root.resolve("logs").resolve("latest-session.properties")));
        } finally {
            interact(context::close);
        }
    }
}
