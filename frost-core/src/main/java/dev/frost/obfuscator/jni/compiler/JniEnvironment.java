package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers JNI include directories from JAVA_HOME or the current runtime.
 */
public final class JniEnvironment {
    public List<Path> includePaths(TargetPlatform targetPlatform) {
        Path javaHome = javaHome();
        List<Path> includes = new ArrayList<>();
        addIfDirectory(includes, javaHome.resolve("include"));
        addIfDirectory(includes, javaHome.resolve("include").resolve(jniMdDirectory(targetPlatform.operatingSystem())));
        return includes;
    }

    public Path javaHome() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            return Path.of(javaHome);
        }
        return Path.of(System.getProperty("java.home"));
    }

    private String jniMdDirectory(OperatingSystem operatingSystem) {
        return switch (operatingSystem) {
            case WINDOWS -> "win32";
            case LINUX -> "linux";
            case MACOS -> "darwin";
        };
    }

    private void addIfDirectory(List<Path> paths, Path path) {
        if (Files.isDirectory(path)) {
            paths.add(path);
        }
    }
}


