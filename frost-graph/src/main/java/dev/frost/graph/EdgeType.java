package dev.frost.graph;

/** Stable edge categories shared by analyzers, plugins, exporters, and viewers. */
public enum EdgeType {
    DEPENDS_ON,
    CALLS,
    EXTENDS,
    IMPLEMENTS,
    PACKAGE_DEPENDENCY,
    FALLTHROUGH,
    CONDITIONAL_TRUE,
    CONDITIONAL_FALSE,
    JUMP,
    SWITCH_CASE,
    SWITCH_DEFAULT,
    EXCEPTION,
    LOOP_BACK,
    CONTAINS,
    EXECUTES_BEFORE,
    REQUIRES,
    CONFLICTS,
    RENAMED_TO,
    GENERATED_BY,
    BEFORE_AFTER,
    INSPECTS,
    MODIFIES,
    VERIFIES,
    CUSTOM
}
