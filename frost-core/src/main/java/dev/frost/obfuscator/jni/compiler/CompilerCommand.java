package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete process invocation for a native compiler.
 */
public record CompilerCommand(List<String> command, Map<String, String> environment, Path workingDirectory) {
    public CompilerCommand {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        Objects.requireNonNull(workingDirectory, "workingDirectory");
    }
}


