package dev.frost.ir.core;

/** Lossless origin coordinates; absent components use -1 rather than inventing a location. */
public record SourcePosition(int bytecodeOffset, int line, int column) {
    public static final SourcePosition UNKNOWN = new SourcePosition(-1, -1, -1);

    public SourcePosition {
        if (bytecodeOffset < -1 || line < -1 || column < -1) {
            throw new IllegalArgumentException("source coordinates must be -1 or non-negative");
        }
    }
}
