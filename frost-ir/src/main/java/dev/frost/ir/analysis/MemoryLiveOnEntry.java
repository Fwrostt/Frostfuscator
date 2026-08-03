package dev.frost.ir.analysis;

public final class MemoryLiveOnEntry implements MemoryVersion {
    private final long id;
    MemoryLiveOnEntry(long id) { this.id = id; }
    @Override public long id() { return id; }
    @Override public String toString() { return "memory.live_on_entry#" + id; }
}
