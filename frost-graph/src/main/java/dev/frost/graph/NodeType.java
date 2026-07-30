package dev.frost.graph;

/** Stable node categories shared by analyzers, plugins, exporters, and viewers. */
public enum NodeType {
    CLASS,
    LIBRARY_CLASS,
    METHOD,
    FIELD,
    PACKAGE,
    BASIC_BLOCK,
    EXCEPTION_HANDLER,
    UNREACHABLE_BLOCK,
    TRANSFORMER,
    PIPELINE_PHASE,
    BUILD_STEP,
    MAPPING,
    GENERATED_MEMBER,
    VERIFICATION,
    WARNING,
    CUSTOM
}
