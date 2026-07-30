package dev.frost.api.event;

/** Receives isolated listener failures without coupling the plugin API to a logging backend. */
@FunctionalInterface
public interface EventExceptionHandler {
    void onListenerException(Object listener, Object event, Throwable failure);
}
