package dev.frost.obfuscator.jni.compiler;

/**
 * MSVC native compiler backend.
 */
public final class MsvcCompilerBackend extends AbstractCompilerBackend {
    public MsvcCompilerBackend(CompilerEnvironment environment) {
        super(environment);
    }

    @Override
    public String name() {
        return "MSVC";
    }

    @Override
    protected CompilerCommandBuilder commandBuilder() {
        return new MsvcCompilerCommandBuilder();
    }
}


