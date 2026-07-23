package dev.frost.api.event;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * High-performance, thread-safe EventBus for Frostfuscator plugin event pub-sub.
 */
public final class EventBus {

    public record HandlerRegistration<E>(
            Object instance,
            Class<E> eventType,
            EventPriority priority,
            boolean ignoreCancelled,
            Consumer<E> handler
    ) implements Comparable<HandlerRegistration<?>> {
        @Override
        public int compareTo(HandlerRegistration<?> other) {
            return Integer.compare(this.priority.slot(), other.priority.slot());
        }
    }

    private final Map<Class<?>, List<HandlerRegistration<?>>> handlerMap = new ConcurrentHashMap<>();
    private final Set<Object> registeredInstances = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Registers all methods marked with @Subscribe on the listener instance.
     */
    public void registerListener(Object listener) {
        if (listener == null || !registeredInstances.add(listener)) return;

        Class<?> clazz = listener.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Subscribe.class)) continue;
            if (method.getParameterCount() != 1) continue;

            Class<?> eventType = method.getParameterTypes()[0];
            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            method.setAccessible(true);

            Consumer<Object> handler = event -> {
                try {
                    method.invoke(listener, event);
                } catch (Exception e) {
                    throw new RuntimeException("Error dispatching event " + event.getClass().getName() + " to " + method, e);
                }
            };

            registerRawHandler(listener, eventType, subscribe.priority(), subscribe.ignoreCancelled(), handler);
        }
    }

    /**
     * Registers a lambda handler for a specific event type.
     */
    public <E> void registerHandler(Class<E> eventType, EventPriority priority, boolean ignoreCancelled, Consumer<E> handler) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");
        registerRawHandler(handler, eventType, priority, ignoreCancelled, handler);
    }

    public <E> void registerHandler(Class<E> eventType, Consumer<E> handler) {
        registerHandler(eventType, EventPriority.NORMAL, true, handler);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerRawHandler(Object owner, Class<?> eventType, EventPriority priority, boolean ignoreCancelled, Consumer handler) {
        HandlerRegistration reg = new HandlerRegistration(owner, eventType, priority, ignoreCancelled, handler);
        handlerMap.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(reg);
        List<HandlerRegistration<?>> list = handlerMap.get(eventType);
        Collections.sort(list);
    }

    /**
     * Unregisters all handlers owned by the given instance.
     */
    public void unregister(Object owner) {
        if (owner == null) return;
        registeredInstances.remove(owner);
        for (List<HandlerRegistration<?>> list : handlerMap.values()) {
            list.removeIf(reg -> reg.instance() == owner);
        }
    }

    /**
     * Dispatches an event to all registered handlers in priority order.
     *
     * @param event event instance
     * @param <E> event type
     * @return the dispatched event
     */
    @SuppressWarnings("unchecked")
    public <E> E post(E event) {
        if (event == null) return null;

        List<HandlerRegistration<?>> handlers = handlerMap.get(event.getClass());
        if (handlers == null || handlers.isEmpty()) return event;

        boolean isCancellable = event instanceof Cancellable;

        for (HandlerRegistration<?> reg : handlers) {
            if (isCancellable && reg.ignoreCancelled() && ((Cancellable) event).isCancelled()) {
                continue;
            }
            try {
                ((Consumer<E>) reg.handler()).accept(event);
            } catch (Exception exception) {
                System.err.println("[FrostBus] Error in event listener " + reg.instance() + ": " + exception.getMessage());
                exception.printStackTrace();
            }
        }

        return event;
    }
}
