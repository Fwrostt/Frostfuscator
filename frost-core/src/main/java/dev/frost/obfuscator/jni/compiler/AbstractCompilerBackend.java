package dev.frost.obfuscator.jni.compiler;

import dev.frost.obfuscator.util.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

abstract class AbstractCompilerBackend implements CompilerBackend {
    private final ProcessRunner processRunner = new ProcessRunner();
    private final LibraryNameResolver libraryNameResolver = new LibraryNameResolver();
    private final CompilerEnvironment environment;

    protected AbstractCompilerBackend(CompilerEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public CompilationResult compile(CompilerInput input) throws IOException, InterruptedException {
        if (input.targets().size() != 1) {
            throw new IOException(name() + " direct invocation supports exactly one target per run.");
        }
        TargetPlatform target = input.targets().get(0);
        Files.createDirectories(input.outputDirectory());
        SourceLayout layout = new NativeSourcePreparer().prepare(input.sourceDirectory(), input.outputDirectory());
        SourceCollector sourceCollector = new SourceCollector();
        List<Path> translationUnits = sourceCollector.collect(layout);
        List<Path> sources = sourceCollector.collectForBuild(layout, input.unityBuild());
        Path outputLibrary = input.outputDirectory().resolve(libraryNameResolver.libraryName(input.libraryBaseName(), target));
        Logger.info("[COMPILER] Detected {}", environment.displayName());
        if (input.unityBuild()) {
            long unityBytes = Files.size(sources.get(0));
            Logger.info("[COMPILER] Unity build merged {} translation units into {} ({} KB)",
                    translationUnits.size(),
                    sources.get(0).getFileName(),
                    Math.max(1L, unityBytes / 1024L));
        }
        Logger.info("[COMPILER] Compiling {} source file{}{}",
                sources.size(),
                sources.size() == 1 ? "" : "s",
                input.unityBuild() ? " (unity build)" : "");
        CompilerCommand command = commandBuilder().build(input, withGeneratedIncludes(environment, layout), target, sources, outputLibrary);
        ProcessResult result = processRunner.run(command);
        if (result.exitCode() != 0) {
            throw new IOException(name() + " failed with exit code " + result.exitCode() + "\n" + result.stderr());
        }
        if (!Files.isRegularFile(outputLibrary)) {
            throw new IOException("Compiler completed but did not produce " + outputLibrary);
        }
        Logger.info("[COMPILER] Generated {}", outputLibrary.getFileName());
        return new CompilationResult(
                List.of(new NativeLibrary(outputLibrary.getFileName().toString(), target, outputLibrary)),
                input.outputDirectory(),
                layout.root().resolve("CMakeLists.txt"),
                result.exitCode(),
                result.stdout(),
                result.stderr()
        );
    }

    protected abstract CompilerCommandBuilder commandBuilder();

    private CompilerEnvironment withGeneratedIncludes(CompilerEnvironment baseEnvironment, SourceLayout layout) {
        List<Path> includePaths = new ArrayList<>();
        includePaths.add(layout.includeDirectory());
        includePaths.add(layout.headersDirectory());
        includePaths.addAll(baseEnvironment.includePaths());
        return new CompilerEnvironment(
                baseEnvironment.executable(),
                includePaths,
                baseEnvironment.environment(),
                baseEnvironment.displayName()
        );
    }
}



