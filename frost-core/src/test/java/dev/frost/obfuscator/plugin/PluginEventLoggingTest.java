package dev.frost.obfuscator.plugin;

import dev.frost.api.event.EventBus;
import dev.frost.obfuscator.util.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginEventLoggingTest {
    private record PluginEvent() { }

    @Test
    void globalPluginListenerFailuresReachFrostLoggerSubscribers() {
        EventBus eventBus = PluginLoader.globalEventBus();
        List<String> logLines = new CopyOnWriteArrayList<>();
        Consumer<String> logListener = logLines::add;
        Consumer<PluginEvent> brokenListener = event -> {
            throw new IllegalStateException("expected plugin listener failure");
        };
        Logger.addListener(logListener);
        eventBus.registerHandler(PluginEvent.class, brokenListener);
        try {
            eventBus.post(new PluginEvent());
            assertTrue(logLines.stream().anyMatch(line -> line.contains("Plugin event listener")
                    && line.contains(PluginEvent.class.getName())));
        } finally {
            eventBus.unregister(brokenListener);
            Logger.removeListener(logListener);
        }
    }
}
