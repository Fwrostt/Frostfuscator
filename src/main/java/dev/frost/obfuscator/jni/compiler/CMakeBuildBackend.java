package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Native backend that builds generated sources through CMake.
 */
public final class CMakeBuildBackend implements CompilerBackend {
    private final Path cmakeExecutable;
    private final ProcessRunner processRunner = new ProcessRunner();
    private final LibraryNameResolver libraryNameResolver = new LibraryNameResolver();
    private final List<Path> includePaths;

    public CMakeBuildBackend(Path cmakeExecutable, List<Path> includePaths) {
        this.cmakeExecutable = cmakeExecutable;
        this.includePaths = List.copyOf(includePaths);
    }

    @Override
    public CompilationResult compile(CompilerInput input) throws IOException, InterruptedException {
        if (input.targets().size() != 1) {
            throw new IOException("CMake backend supports exactly one target per run.");
        }
        TargetPlatform target = input.targets().get(0);
        Files.createDirectories(input.outputDirectory());
        SourceLayout layout = new NativeSourcePreparer().prepare(input.sourceDirectory(), input.outputDirectory());
        List<Path> sources = new SourceCollector().collectForBuild(layout, input.unityBuild());
        Path cmakeLists = new CMakeGenerator().generate(layout, input.libraryBaseName(), sources, includePaths);
        Path cmakeBuild = input.outputDirectory().resolve("cmake-build");
        Files.createDirectories(cmakeBuild);

        ProcessResult configure = processRunner.run(new CompilerCommand(
                List.of(cmakeExecutable.toString(), "-S", layout.root().toString(), "-B", cmakeBuild.toString()),
                Map.of(),
                input.outputDirectory()
        ));
        if (configure.exitCode() != 0) {
            throw new IOException("CMake configure failed\n" + configure.stderr());
        }

        ProcessResult build = processRunner.run(new CompilerCommand(
                List.of(cmakeExecutable.toString(), "--build", cmakeBuild.toString(), "--config", "Release"),
                Map.of(),
                input.outputDirectory()
        ));
        if (build.exitCode() != 0) {
            throw new IOException("CMake build failed\n" + build.stderr());
        }

        String libraryName = libraryNameResolver.libraryName(input.libraryBaseName(), target);
        Path outputLibrary = findBuiltLibrary(cmakeBuild, libraryName);
        return new CompilationResult(
                List.of(new NativeLibrary(libraryName, target, outputLibrary)),
                input.outputDirectory(),
                cmakeLists,
                build.exitCode(),
                configure.stdout() + build.stdout(),
                configure.stderr() + build.stderr()
        );
    }

    @Override
    public String name() {
        return "CMake";
    }

    private Path findBuiltLibrary(Path buildDirectory, String libraryName) throws IOException {
        try (var stream = Files.walk(buildDirectory)) {
            return stream.filter(path -> path.getFileName().toString().equals(libraryName))
                    .findFirst()
                    .orElseThrow(() -> new IOException("CMake did not produce " + libraryName));
        }
    }
}


