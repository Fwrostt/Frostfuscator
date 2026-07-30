package dev.frost.obfuscator.jni.compiler;

/** Zig native compiler backend using the bundled Clang-compatible C++ driver. */
public final class ZigCompilerBackend extends AbstractCompilerBackend {
    public ZigCompilerBackend(CompilerEnvironment environment) {
        super(environment);
    }

    @Override public String name() { return "Zig"; }

    @Override protected CompilerCommandBuilder commandBuilder() {
        return new ZigCompilerCommandBuilder();
    }
}
