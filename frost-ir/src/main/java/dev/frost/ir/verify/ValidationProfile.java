package dev.frost.ir.verify;

public enum ValidationProfile {
    /** Ownership, edge incidence, operation shape, and use-def consistency. */
    STRUCTURAL,
    /** Full executable CFG, phi completeness, type compatibility, and SSA dominance. */
    STRICT,
    /** Strict plus bytecode-lowering preconditions. */
    LOWERABLE
}
