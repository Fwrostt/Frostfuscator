package dev.frost.obfuscator.jni.core.model;

/**
 * Broad JVM instruction categories used before lowering to IR.
 */
public enum InstructionKind {
    LABEL,
    CONSTANT,
    VARIABLE,
    ARITHMETIC,
    RETURN,
    BRANCH,
    METHOD_CALL,
    FIELD_ACCESS,
    STACK,
    OTHER
}


