package dev.frost.api.event;

/**
 * Execution priority for event listeners.
 * Listeners are invoked in order from HIGHEST down to LOWEST, followed by MONITOR.
 */
public enum EventPriority {
    HIGHEST(1),
    HIGH(2),
    NORMAL(3),
    LOW(4),
    LOWEST(5),
    /** Monitor priority is invoked last and should not modify event state. */
    MONITOR(6);

    private final int slot;

    EventPriority(int slot) {
        this.slot = slot;
    }

    public int slot() {
        return slot;
    }
}
