package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds GCC shared-library commands.
 */
public class GccCompilerCommandBuilder implements CompilerCommandBuilder {
    @Override
    public CompilerCommand build(
            CompilerInput input,
            CompilerEnvironment environment,
            TargetPlatform target,
            List<Path> sources,
            Path outputLibrary
    ) {
        List<String> command = new ArrayList<>();
        command.add(environment.executable().toString());
        command.add("-std=c++17");
        command.add("-shared");
        command.add("-" + input.optimizationLevel().replaceFirst("^-", ""));
        if (target.operatingSystem() != OperatingSystem.WINDOWS) {
            command.add("-fPIC");
        }
        for (Path includePath : environment.includePaths()) {
            command.add("-I" + includePath.toAbsolutePath());
        }
        sources.forEach(source -> command.add(source.toAbsolutePath().toString()));
        command.add("-o");
        command.add(outputLibrary.toAbsolutePath().toString());
        if (input.stripSymbols()) {
            command.add("-s");
        }
        return new CompilerCommand(command, environment.environment(), input.outputDirectory());
    }
}


