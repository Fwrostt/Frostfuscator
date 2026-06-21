package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Creates concrete compiler backends from detected compilers.
 */
public final class CompilerBackendFactory {
    private final JniEnvironment jniEnvironment = new JniEnvironment();
    private final WindowsCppRuntimeDetector windowsCppRuntimeDetector = new WindowsCppRuntimeDetector();

    public CompilerBackend create(DetectedCompiler detectedCompiler, TargetPlatform targetPlatform) throws IOException {
        if (targetPlatform.operatingSystem() == OperatingSystem.WINDOWS
                && detectedCompiler.kind() == CompilerKind.CLANG
                && !windowsCppRuntimeDetector.hasRuntimeHeaders()) {
            throw new IOException("Clang was detected at " + detectedCompiler.executable()
                    + ", but Windows C/C++ runtime headers were not found. Install Visual Studio Build Tools with the Windows SDK, or install MinGW-w64 and put g++ on PATH.");
        }
        CompilerEnvironment environment = new CompilerEnvironment(
                detectedCompiler.executable(),
                jniEnvironment.includePaths(targetPlatform),
                Map.of(),
                detectedCompiler.displayName()
        );
        if (environment.includePaths().size() < 2) {
            throw new IOException("Unable to locate JNI include directories under " + jniEnvironment.javaHome());
        }
        return switch (detectedCompiler.kind()) {
            case GCC -> new GccCompilerBackend(environment);
            case CLANG -> new ClangCompilerBackend(environment);
            case MSVC -> new MsvcCompilerBackend(environment);
        };
    }

    public List<java.nio.file.Path> jniIncludePaths(TargetPlatform targetPlatform) {
        return jniEnvironment.includePaths(targetPlatform);
    }
}


