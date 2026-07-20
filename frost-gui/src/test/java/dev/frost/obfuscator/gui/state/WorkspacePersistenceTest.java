package dev.frost.obfuscator.gui.state;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.console.LogEntry;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkspacePersistenceTest extends ApplicationTest {
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    @Test
    void restoresWorkingConfigurationProfileHistoryAndConsole() throws Exception {
        Path root = Files.createTempDirectory("frostfuscator-workspace-test");
        AtomicReference<AppContext> first = new AtomicReference<>();
        interact(() -> {
            AppContext context = AppContext.create(stage, new PreferencesStore(root));
            first.set(context);
            context.projectState().configuration().setInput("D:\\work\\input.jar");
            context.projectState().configuration().setOutput("D:\\work\\protected.jar");
            context.projectState().profileProperty().set("Strong");
            context.projectState().goalProperty().set("Strongest protection");
            context.projectState().outputSizeLimitMbProperty().set(42);
            context.projectState().touch();
            context.consoleModel().append(LogEntry.Level.INFO, "Persist this console line");
            context.close();
        });

        assertTrue(Files.isRegularFile(root.resolve("workspace").resolve("session.yml")));
        assertTrue(Files.isRegularFile(root.resolve("logs").resolve("latest-session.properties")));

        AtomicReference<AppContext> restored = new AtomicReference<>();
        interact(() -> restored.set(AppContext.create(stage, new PreferencesStore(root))));
        AppContext context = restored.get();
        try {
            assertEquals("D:\\work\\input.jar", context.projectState().configuration().getInput());
            assertEquals("D:\\work\\protected.jar", context.projectState().configuration().getOutput());
            assertEquals("Strong", context.projectState().profileProperty().get());
            assertEquals("Strongest protection", context.projectState().goalProperty().get());
            assertEquals(42, context.projectState().outputSizeLimitMbProperty().get(), 0.001);
            assertTrue(context.consoleModel().entries().stream()
                    .anyMatch(entry -> entry.message().contains("Persist this console line")));
        } finally {
            interact(context::close);
        }
    }
}
