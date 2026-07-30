package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds a C++ command through Zig's Clang-compatible driver. */
public final class ZigCompilerCommandBuilder extends GccCompilerCommandBuilder {
    @Override
    public CompilerCommand build(CompilerInput input, CompilerEnvironment environment,
                                 TargetPlatform target, List<Path> sources, Path outputLibrary) {
        CompilerCommand base = super.build(input, environment, target, sources, outputLibrary);
        List<String> command = new ArrayList<>(base.command());
        command.add(1, "c++");
        return new CompilerCommand(command, base.environment(), base.workingDirectory());
    }
}
