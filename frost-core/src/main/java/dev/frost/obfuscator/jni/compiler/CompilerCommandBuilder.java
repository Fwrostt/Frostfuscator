package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;
import java.util.List;

/**
 * Builds a compiler command for one target platform.
 */
public interface CompilerCommandBuilder {
    CompilerCommand build(
            CompilerInput input,
            CompilerEnvironment environment,
            TargetPlatform target,
            List<java.nio.file.Path> sources,
            java.nio.file.Path outputLibrary
    ) throws IOException;
}


