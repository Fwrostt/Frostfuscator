package dev.frost.ir.analysis;

import dev.frost.ir.model.IrInstruction;
import java.util.Objects;

public final class MemoryDef implements MemoryVersion {
    private final long id;
    private final IrInstruction instruction;
    private final MemoryVersion previous;
    private final MemoryLocation location;

    MemoryDef(long id, IrInstruction instruction, MemoryVersion previous, MemoryLocation location) {
        this.id = id;
        this.instruction = Objects.requireNonNull(instruction, "instruction");
        this.previous = Objects.requireNonNull(previous, "previous");
        this.location = Objects.requireNonNull(location, "location");
    }

    @Override public long id() { return id; }
    public IrInstruction instruction() { return instruction; }
    public MemoryVersion previous() { return previous; }
    public MemoryLocation location() { return location; }
    @Override public String toString() { return "memory.def#" + id + "(" + instruction + ")"; }
}
