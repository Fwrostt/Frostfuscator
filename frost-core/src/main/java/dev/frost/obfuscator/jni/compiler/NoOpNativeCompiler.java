package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Foundation compiler backend that records compilation intent without invoking
 * a platform toolchain yet.
 */
public final class NoOpNativeCompiler implements CompilerBackend {
    @Override
    public CompilationResult compile(CompilerInput input) throws IOException {
        Files.createDirectories(input.outputDirectory());
        Path manifest = input.outputDirectory().resolve("frostjni-compiler-manifest.txt");
        long sourceCount;
        try (var stream = Files.list(input.sourceDirectory())) {
            sourceCount = stream.filter(path -> path.getFileName().toString().endsWith(".cpp")).count();
        }
        Files.writeString(
                manifest,
                "backend=noop\n"
                        + "libraryBaseName=" + input.libraryBaseName() + "\n"
                        + "sourceDirectory=" + input.sourceDirectory().toAbsolutePath() + "\n"
                        + "sourceCount=" + sourceCount + "\n"
                        + "targets=" + input.targets() + "\n",
                StandardCharsets.UTF_8
        );
        return new CompilationResult(List.of(), input.outputDirectory(), manifest, 0, "", "");
    }

    @Override
    public String name() {
        return "noop";
    }
}


