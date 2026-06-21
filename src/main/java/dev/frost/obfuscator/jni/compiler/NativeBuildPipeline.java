package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * End-to-end native build pipeline for generated FrostJNI C++ sources.
 */
public final class NativeBuildPipeline {
    private final CompilerDetector compilerDetector = new CompilerDetector();
    private final CompilerBackendFactory backendFactory = new CompilerBackendFactory();
    private final CMakeGenerator cmakeGenerator = new CMakeGenerator();
    private final NativeLibraryVerifier verifier = new NativeLibraryVerifier();

    public CompilationResult build(CompilerInput input, JniSymbolRegistry symbolRegistry) throws IOException, InterruptedException {
        return build(input, symbolRegistry, Set.of(CompilerKind.CLANG, CompilerKind.GCC, CompilerKind.MSVC));
    }

    public CompilationResult build(
            CompilerInput input,
            JniSymbolRegistry symbolRegistry,
            Set<CompilerKind> allowedCompilers
    ) throws IOException, InterruptedException {
        if (input.targets().size() != 1) {
            throw new IOException("NativeBuildPipeline currently supports one target per invocation.");
        }
        TargetPlatform target = input.targets().get(0);
        SourceLayout layout = new NativeSourcePreparer().prepare(input.sourceDirectory(), input.outputDirectory());
        List<java.nio.file.Path> sources = new SourceCollector().collectForBuild(layout, input.unityBuild());
        cmakeGenerator.generate(layout, input.libraryBaseName(), sources, backendFactory.jniIncludePaths(target));
        cleanupStagedSourcesForBackend(input, layout);

        List<DetectedCompiler> detectedCompilers = compilerDetector.detectAll(target).stream()
                .filter(compiler -> allowedCompilers.contains(compiler.kind()))
                .toList();
        if (detectedCompilers.isEmpty()) {
            throw new IOException("No supported C++ compiler found. Install MSVC, MinGW GCC, or Clang and ensure it is discoverable.");
        }

        List<String> failures = new ArrayList<>();
        for (DetectedCompiler detectedCompiler : detectedCompilers) {
            try {
                CompilerBackend backend = backendFactory.create(detectedCompiler, target);
                CompilationResult result = backend.compile(input);
                verifier.verify(result, symbolRegistry);
                return result;
            } catch (IOException exception) {
                failures.add(detectedCompiler.displayName() + " at " + detectedCompiler.executable() + ": " + exception.getMessage());
            }
        }
        throw new IOException("No detected C++ compiler could build the native library. Tried: " + String.join(" | ", failures));
    }

    private void cleanupStagedSourcesForBackend(CompilerInput input, SourceLayout layout) throws IOException {
        // The selected backend prepares the same deterministic layout before compilation.
        // CMakeLists remains useful for debugging even if direct invocation is used.
        Files.createDirectories(layout.root());
    }
}


