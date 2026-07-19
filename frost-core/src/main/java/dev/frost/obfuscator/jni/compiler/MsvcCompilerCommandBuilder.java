package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds MSVC shared-library commands.
 */
public final class MsvcCompilerCommandBuilder implements CompilerCommandBuilder {
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
        command.add("/nologo");
        command.add("/EHsc");
        command.add("/LD");
        for (Path includePath : environment.includePaths()) {
            command.add("/I" + includePath.toAbsolutePath());
        }
        sources.forEach(source -> command.add(source.toAbsolutePath().toString()));
        command.add("/link");
        command.add("/OUT:" + outputLibrary.toAbsolutePath());
        return new CompilerCommand(command, environment.environment(), input.outputDirectory());
    }
}


