package dev.frost.api.event;

import dev.frost.api.event.events.PreObfuscationEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    static class TestEvent implements Cancellable {
        private final String message;
        private boolean cancelled;

        public TestEvent(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }

    static class TestListener {
        final List<String> received = new ArrayList<>();

        @Subscribe(priority = EventPriority.HIGH)
        void onHigh(TestEvent event) {
            received.add("HIGH:" + event.message());
        }

        @Subscribe(priority = EventPriority.NORMAL)
        void onNormal(TestEvent event) {
            received.add("NORMAL:" + event.message());
            if (event.message().equals("cancel-me")) {
                event.setCancelled(true);
            }
        }

        @Subscribe(priority = EventPriority.LOW, ignoreCancelled = true)
        void onLowIgnored(TestEvent event) {
            received.add("LOW:" + event.message());
        }
    }

    @Test
    void testEventDispatchAndPriorityOrder() {
        EventBus bus = new EventBus();
        TestListener listener = new TestListener();
        bus.registerListener(listener);

        bus.post(new TestEvent("hello"));

        assertEquals(3, listener.received.size());
        assertEquals("HIGH:hello", listener.received.get(0));
        assertEquals("NORMAL:hello", listener.received.get(1));
        assertEquals("LOW:hello", listener.received.get(2));
    }

    @Test
    void testEventCancellation() {
        EventBus bus = new EventBus();
        TestListener listener = new TestListener();
        bus.registerListener(listener);

        TestEvent event = bus.post(new TestEvent("cancel-me"));

        assertTrue(event.isCancelled());
        // HIGH and NORMAL ran, LOW was skipped because ignoreCancelled = true
        assertEquals(2, listener.received.size());
        assertEquals("HIGH:cancel-me", listener.received.get(0));
        assertEquals("NORMAL:cancel-me", listener.received.get(1));
    }

    @Test
    void testLambdaHandlerRegistration() {
        EventBus bus = new EventBus();
        List<String> log = new ArrayList<>();

        bus.registerHandler(PreObfuscationEvent.class, event -> {
            log.add("PreObfuscation:" + event.classPool().size());
        });

        Map<String, Object> config = new HashMap<>();
        PreObfuscationEvent event = new PreObfuscationEvent(null, Map.of(), Map.of(), config);
        bus.post(event);

        assertEquals(1, log.size());
        assertEquals("PreObfuscation:0", log.get(0));
    }

    @Test
    void equalPriorityHandlersKeepRegistrationOrder() {
        EventBus bus = new EventBus();
        List<String> log = new ArrayList<>();

        bus.registerHandler(TestEvent.class, EventPriority.NORMAL, false, event -> log.add("first"));
        bus.registerHandler(TestEvent.class, EventPriority.HIGHEST, false, event -> log.add("highest"));
        bus.registerHandler(TestEvent.class, EventPriority.NORMAL, false, event -> log.add("second"));
        bus.registerHandler(TestEvent.class, EventPriority.MONITOR, false, event -> log.add("monitor"));

        bus.post(new TestEvent("ordered"));

        assertEquals(List.of("highest", "first", "second", "monitor"), log);
    }

    @Test
    void testUnregister() {
        EventBus bus = new EventBus();
        TestListener listener = new TestListener();
        bus.registerListener(listener);

        bus.unregister(listener);
        bus.post(new TestEvent("test"));

        assertTrue(listener.received.isEmpty(), "Unregistered listener should not receive events");
    }
}
