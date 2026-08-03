package dev.frost.ir.analysis;

public record IntegerRange(long minimum, long maximum) {
    public IntegerRange {
        if (minimum > maximum) throw new IllegalArgumentException("minimum exceeds maximum");
    }

    public static IntegerRange exact(long value) { return new IntegerRange(value, value); }
    public boolean isExact() { return minimum == maximum; }
    public IntegerRange union(IntegerRange other) {
        return new IntegerRange(Math.min(minimum, other.minimum), Math.max(maximum, other.maximum));
    }
    public boolean contains(long value) { return value >= minimum && value <= maximum; }
}
