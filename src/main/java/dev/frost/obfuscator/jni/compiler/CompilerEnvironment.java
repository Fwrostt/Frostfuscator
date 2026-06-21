package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Native compiler executable plus include paths and environment variables.
 */
public record CompilerEnvironment(Path executable, List<Path> includePaths, Map<String, String> environment, String displayName) {
    public CompilerEnvironment {
        Objects.requireNonNull(executable, "executable");
        includePaths = List.copyOf(Objects.requireNonNull(includePaths, "includePaths"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        Objects.requireNonNull(displayName, "displayName");
    }
}


