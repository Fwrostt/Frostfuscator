package dev.frost.ir.model;

public enum OperationTrait {
    TERMINATOR,
    COMMUTATIVE,
    ASSOCIATIVE,
    IDEMPOTENT,
    CONSTANT_LIKE,
    CALL_LIKE,
    MEMORY_READ,
    MEMORY_WRITE,
    ALLOCATION,
    PINNED,
    SPECULATABLE,
    HAS_NESTED_REGIONS,
    JVM_PSEUDO,
    OPAQUE
}
