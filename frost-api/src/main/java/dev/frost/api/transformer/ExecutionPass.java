package dev.frost.api.transformer;

/**
 * Defines the pass order during obfuscation engine pipeline execution.
 */
public enum ExecutionPass {
    /** Run before core transformations begin (e.g. analysis, symbol collection, class additions) */
    PRE_PROCESSING(100),
    /** Main obfuscation transformations (e.g. flow obfuscation, string encryption, indirection) */
    PRIMARY(200),
    /** Run after main transformations (e.g. access modifier cleanup, anti-agent checks) */
    POST_PROCESSING(300),
    /** Final passes prior to writing output JAR (e.g. decompiler crasher, metadata stripping) */
    FINALIZATION(400);

    private final int order;

    ExecutionPass(int order) {
        this.order = order;
    }

    public int order() {
        return order;
    }
}
