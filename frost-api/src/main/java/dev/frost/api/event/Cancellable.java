package dev.frost.api.event;

/**
 * Interface for events that can be cancelled by listeners.
 */
public interface Cancellable {

    /**
     * @return true if event has been cancelled by a listener
     */
    boolean isCancelled();

    /**
     * Sets the cancelled status of the event.
     *
     * @param cancelled true to cancel execution, false otherwise
     */
    void setCancelled(boolean cancelled);
}
