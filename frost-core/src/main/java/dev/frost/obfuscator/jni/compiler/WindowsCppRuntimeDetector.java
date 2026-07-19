package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Checks whether Windows C/C++ runtime headers needed by Clang/MSVC are
 * available.
 */
public final class WindowsCppRuntimeDetector {
    public boolean hasRuntimeHeaders() {
        return includeEnvironmentHasStdio()
                || windowsKitHasStdio()
                || visualCppHasStdio();
    }

    private boolean includeEnvironmentHasStdio() {
        String include = System.getenv("INCLUDE");
        if (include == null || include.isBlank()) {
            return false;
        }
        return Arrays.stream(include.split(java.io.File.pathSeparator))
                .map(Path::of)
                .map(path -> path.resolve("stdio.h"))
                .anyMatch(Files::isRegularFile);
    }

    private boolean windowsKitHasStdio() {
        Path includeRoot = Path.of("C:/Program Files (x86)/Windows Kits/10/Include");
        if (!Files.isDirectory(includeRoot)) {
            return false;
        }
        try (Stream<Path> versions = Files.list(includeRoot)) {
            return versions
                    .map(path -> path.resolve("ucrt").resolve("stdio.h"))
                    .anyMatch(Files::isRegularFile);
        } catch (java.io.IOException exception) {
            return false;
        }
    }

    private boolean visualCppHasStdio() {
        return visualCppRootHasStdio(Path.of("C:/Program Files/Microsoft Visual Studio"))
                || visualCppRootHasStdio(Path.of("C:/Program Files (x86)/Microsoft Visual Studio"));
    }

    private boolean visualCppRootHasStdio(Path root) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(root, 8)) {
            return paths
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("stdio.h"))
                    .anyMatch(path -> path.toString().contains("\\VC\\Tools\\MSVC\\"));
        } catch (java.io.IOException exception) {
            return false;
        }
    }
}


