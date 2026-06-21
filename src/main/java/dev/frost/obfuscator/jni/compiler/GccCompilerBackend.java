package dev.frost.obfuscator.jni.compiler;

/**
 * GCC native compiler backend.
 */
public final class GccCompilerBackend extends AbstractCompilerBackend {
    public GccCompilerBackend(CompilerEnvironment environment) {
        super(environment);
    }

    @Override
    public String name() {
        return "GCC";
    }

    @Override
    protected CompilerCommandBuilder commandBuilder() {
        return new GccCompilerCommandBuilder();
    }
}


