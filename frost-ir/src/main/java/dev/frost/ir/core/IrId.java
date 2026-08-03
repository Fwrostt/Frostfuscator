package dev.frost.ir.core;

/** A deterministic, method-local identity. Equality is meaningful only inside one owner. */
public record IrId(long value) implements Comparable<IrId> {
    public IrId {
        if (value < 0) throw new IllegalArgumentException("IR ids must be non-negative");
    }

    @Override
    public int compareTo(IrId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(value);
    }
}
