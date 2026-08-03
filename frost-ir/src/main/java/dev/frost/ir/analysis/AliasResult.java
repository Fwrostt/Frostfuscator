package dev.frost.ir.analysis;

public enum AliasResult {
    NO_ALIAS,
    MAY_ALIAS,
    PARTIAL_ALIAS,
    MUST_ALIAS;

    public boolean mayAlias() { return this != NO_ALIAS; }
}
