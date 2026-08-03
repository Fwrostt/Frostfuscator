package dev.frost.ir.model;

public enum EdgeKind {
    FALLTHROUGH(false),
    NORMAL(false),
    TRUE(false),
    FALSE(false),
    SWITCH_CASE(false),
    SWITCH_DEFAULT(false),
    EXCEPTION(true),
    FINALLY(true),
    SUBROUTINE_RETURN(false),
    SYNTHETIC(false);

    private final boolean exceptional;

    EdgeKind(boolean exceptional) { this.exceptional = exceptional; }
    public boolean isExceptional() { return exceptional; }
}
