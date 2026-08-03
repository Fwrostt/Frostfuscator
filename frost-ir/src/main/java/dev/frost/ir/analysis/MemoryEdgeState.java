package dev.frost.ir.analysis;

import dev.frost.ir.model.ControlEdge;
import java.util.Objects;

/** Conservative memory state crossing an exceptional edge from a multi-instruction block. */
public final class MemoryEdgeState implements MemoryVersion {
    private final long id;
    private final ControlEdge edge;
    private final MemoryVersion normalExit;

    MemoryEdgeState(long id, ControlEdge edge, MemoryVersion normalExit) {
        this.id = id;
        this.edge = Objects.requireNonNull(edge, "edge");
        this.normalExit = Objects.requireNonNull(normalExit, "normalExit");
    }

    @Override public long id() { return id; }
    public ControlEdge edge() { return edge; }
    public MemoryVersion normalExit() { return normalExit; }
    @Override public String toString() { return "memory.exception_edge#" + id + "(" + edge + ")"; }
}
