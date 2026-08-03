package dev.frost.ir.analysis;

import dev.frost.ir.model.IrInstruction;
import java.util.Objects;

public final class MemoryUse implements MemoryAccess {
    private final long id;
    private final IrInstruction instruction;
    private final MemoryVersion reachingVersion;
    private final MemoryLocation location;

    MemoryUse(long id, IrInstruction instruction, MemoryVersion reachingVersion, MemoryLocation location) {
        this.id = id;
        this.instruction = Objects.requireNonNull(instruction, "instruction");
        this.reachingVersion = Objects.requireNonNull(reachingVersion, "reachingVersion");
        this.location = Objects.requireNonNull(location, "location");
    }

    @Override public long id() { return id; }
    public IrInstruction instruction() { return instruction; }
    public MemoryVersion reachingVersion() { return reachingVersion; }
    public MemoryLocation location() { return location; }
    @Override public String toString() { return "memory.use#" + id + "(" + instruction + ")"; }
}
