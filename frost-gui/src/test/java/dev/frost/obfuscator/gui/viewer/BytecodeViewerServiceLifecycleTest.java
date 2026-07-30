package dev.frost.obfuscator.gui.viewer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeViewerServiceLifecycleTest {
    @Test
    void closeRemovesShutdownHookAndStopsDecompilerExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingShutdownHooks hooks = new RecordingShutdownHooks();
        BytecodeViewerService service = new BytecodeViewerService(executor, hooks);

        assertNotNull(hooks.registered);
        service.close();

        assertSame(hooks.registered, hooks.removed);
        assertTrue(executor.isShutdown());
        assertDoesNotThrow(service::close, "close must remain idempotent");
    }

    @Test
    void jvmShutdownHookStopsDecompilerExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingShutdownHooks hooks = new RecordingShutdownHooks();
        BytecodeViewerService service = new BytecodeViewerService(executor, hooks);

        hooks.registered.run();

        assertTrue(executor.isShutdown());
        assertDoesNotThrow(service::close);
    }

    private static final class RecordingShutdownHooks
            implements BytecodeViewerService.ShutdownHookRegistry {
        private Thread registered;
        private Thread removed;

        @Override
        public void add(Thread hook) {
            registered = hook;
        }

        @Override
        public boolean remove(Thread hook) {
            removed = hook;
            return true;
        }
    }
}
