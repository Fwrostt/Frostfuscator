package dev.frost.obfuscator.jni.compiler;

/**
 * Clang native compiler backend.
 */
public final class ClangCompilerBackend extends AbstractCompilerBackend {
    public ClangCompilerBackend(CompilerEnvironment environment) {
        super(environment);
    }

    @Override
    public String name() {
        return "Clang";
    }

    @Override
    protected CompilerCommandBuilder commandBuilder() {
        return new ClangCompilerCommandBuilder();
    }
}


