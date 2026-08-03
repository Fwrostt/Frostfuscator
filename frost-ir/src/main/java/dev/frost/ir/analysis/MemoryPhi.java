package dev.frost.ir.analysis;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Edge-keyed memory merge. Inputs are frozen before the analysis becomes observable. */
public final class MemoryPhi implements MemoryVersion {
    private final long id;
    private final BasicBlock block;
    private final Map<ControlEdge, MemoryVersion> inputs = new LinkedHashMap<>();
    private boolean frozen;

    MemoryPhi(long id, BasicBlock block) {
        this.id = id;
        this.block = Objects.requireNonNull(block, "block");
    }

    @Override public long id() { return id; }
    public BasicBlock block() { return block; }
    public Map<ControlEdge, MemoryVersion> inputs() { return Collections.unmodifiableMap(inputs); }

    void putInput(ControlEdge edge, MemoryVersion version) {
        if (frozen) throw new IllegalStateException("memory phi is frozen");
        inputs.put(Objects.requireNonNull(edge, "edge"), Objects.requireNonNull(version, "version"));
    }

    void freeze() { frozen = true; }
    @Override public String toString() { return "memory.phi#" + id + "(" + block + ")"; }
}
