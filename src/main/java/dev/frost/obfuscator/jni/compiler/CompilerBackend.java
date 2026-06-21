package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;

/**
 * Native compiler backend abstraction.
 */
public interface CompilerBackend {
    CompilationResult compile(CompilerInput input) throws IOException, InterruptedException;

    String name();
}


