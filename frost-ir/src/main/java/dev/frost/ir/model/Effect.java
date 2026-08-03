package dev.frost.ir.model;

/** Observable or ordering-relevant effects. */
public enum Effect {
    READ_HEAP,
    WRITE_HEAP,
    READ_STATIC,
    WRITE_STATIC,
    READ_ARRAY,
    WRITE_ARRAY,
    ALLOCATE,
    INVOKE,
    DYNAMIC_LINKAGE,
    MONITOR,
    VOLATILE,
    NATIVE,
    IO,
    MAY_THROW,
    MAY_DEOPTIMIZE,
    CONTROL_FLOW,
    UNKNOWN
}
