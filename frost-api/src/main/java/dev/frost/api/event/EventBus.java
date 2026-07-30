package dev.frost.api.event;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * High-performance, thread-safe EventBus for Frostfuscator plugin event pub-sub.
 */
public final class EventBus {
    private static final System.Logger SYSTEM_LOGGER = System.getLogger(EventBus.class.getName());

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

    private final Map<Class<?>, AtomicReference<List<HandlerRegistration<?>>>> handlerMap =
            new ConcurrentHashMap<>();
    private final Set<Object> registeredInstances = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final EventExceptionHandler exceptionHandler;

    public EventBus() {
        this(EventBus::logListenerException);
    }

    public EventBus(EventExceptionHandler exceptionHandler) {
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

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
        Objects.requireNonNull(priority, "priority");
        HandlerRegistration<?> registration =
                new HandlerRegistration(owner, eventType, priority, ignoreCancelled, handler);
        AtomicReference<List<HandlerRegistration<?>>> handlers = handlerMap.computeIfAbsent(eventType,
                ignored -> new AtomicReference<>(List.of()));
        while (true) {
            List<HandlerRegistration<?>> current = handlers.get();
            int low = 0;
            int high = current.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (current.get(middle).compareTo(registration) <= 0) low = middle + 1;
                else high = middle;
            }
            List<HandlerRegistration<?>> updated = new ArrayList<>(current.size() + 1);
            updated.addAll(current.subList(0, low));
            updated.add(registration);
            updated.addAll(current.subList(low, current.size()));
            if (handlers.compareAndSet(current, List.copyOf(updated))) return;
        }
    }

    /**
     * Unregisters all handlers owned by the given instance.
     */
    public void unregister(Object owner) {
        if (owner == null) return;
        registeredInstances.remove(owner);
        handlerMap.values().forEach(handlers -> removeMatching(handlers, reg -> reg.instance() == owner));
    }

    /** Removes every listener whose owner was defined by the supplied plugin class loader. */
    public int unregisterClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return 0;
        int removed = 0;
        for (AtomicReference<List<HandlerRegistration<?>>> handlers : handlerMap.values()) {
            removed += removeMatching(handlers, registration -> registration.instance() != null
                    && registration.instance().getClass().getClassLoader() == classLoader);
        }
        registeredInstances.removeIf(instance -> instance != null
                && instance.getClass().getClassLoader() == classLoader);
        return removed;
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

        AtomicReference<List<HandlerRegistration<?>>> registrations = handlerMap.get(event.getClass());
        if (registrations == null) return event;
        List<HandlerRegistration<?>> handlers = registrations.get();
        if (handlers.isEmpty()) return event;

        boolean isCancellable = event instanceof Cancellable;

        for (HandlerRegistration<?> reg : handlers) {
            if (isCancellable && reg.ignoreCancelled() && ((Cancellable) event).isCancelled()) {
                continue;
            }
            try {
                ((Consumer<E>) reg.handler()).accept(event);
            } catch (Exception exception) {
                reportListenerException(reg.instance(), event, exception);
            }
        }

        return event;
    }

    private void reportListenerException(Object listener, Object event, Throwable failure) {
        try {
            exceptionHandler.onListenerException(listener, event, failure);
        } catch (RuntimeException handlerFailure) {
            SYSTEM_LOGGER.log(System.Logger.Level.ERROR,
                    "Event exception handler failed while reporting listener " + listener, handlerFailure);
        }
    }

    private static int removeMatching(AtomicReference<List<HandlerRegistration<?>>> handlers,
                                      Predicate<HandlerRegistration<?>> predicate) {
        while (true) {
            List<HandlerRegistration<?>> current = handlers.get();
            List<HandlerRegistration<?>> updated = current.stream().filter(predicate.negate()).toList();
            int removed = current.size() - updated.size();
            if (removed == 0 || handlers.compareAndSet(current, updated)) return removed;
        }
    }

    private static void logListenerException(Object listener, Object event, Throwable failure) {
        SYSTEM_LOGGER.log(System.Logger.Level.ERROR,
                "Error in event listener " + listener + " while handling " + event.getClass().getName(), failure);
    }
}
