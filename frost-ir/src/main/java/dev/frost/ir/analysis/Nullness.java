package dev.frost.ir.analysis;

public enum Nullness {
    BOTTOM,
    NULL,
    NON_NULL,
    MAYBE_NULL;

    public Nullness join(Nullness other) {
        if (this == BOTTOM) return other;
        if (other == BOTTOM || this == other) return this;
        return MAYBE_NULL;
    }
}
