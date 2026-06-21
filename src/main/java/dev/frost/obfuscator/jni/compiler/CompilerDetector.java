package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Locates available native compilers for the current machine.
 */
public final class CompilerDetector {
    public Optional<DetectedCompiler> detect(TargetPlatform targetPlatform) {
        List<DetectedCompiler> compilers = detectAll(targetPlatform);
        return compilers.isEmpty() ? Optional.empty() : Optional.of(compilers.get(0));
    }

    public List<DetectedCompiler> detectAll(TargetPlatform targetPlatform) {
        List<DetectedCompiler> compilers = new ArrayList<>();
        if (targetPlatform.operatingSystem() == OperatingSystem.WINDOWS) {
            findExecutable("clang++", List.of(
                    Path.of("C:/Program Files/LLVM/bin/clang++.exe"),
                    Path.of("C:/Program Files (x86)/LLVM/bin/clang++.exe")
            )).map(path -> new DetectedCompiler(CompilerKind.CLANG, path, "Clang")).ifPresent(compilers::add);
            findExecutable("g++", List.of(
                    Path.of("C:/msys64/ucrt64/bin/g++.exe"),
                    Path.of("C:/msys64/mingw64/bin/g++.exe"),
                    Path.of("C:/msys64/clang64/bin/g++.exe"),
                    Path.of("C:/mingw64/bin/g++.exe")
            )).map(path -> new DetectedCompiler(CompilerKind.GCC, path, "MinGW GCC")).ifPresent(compilers::add);
            detectMsvc().ifPresent(compilers::add);
        } else if (targetPlatform.operatingSystem() == OperatingSystem.MACOS) {
            findOnPath("clang++").map(path -> new DetectedCompiler(CompilerKind.CLANG, path, "Clang")).ifPresent(compilers::add);
        } else {
            findOnPath("g++").map(path -> new DetectedCompiler(CompilerKind.GCC, path, "GCC")).ifPresent(compilers::add);
            findOnPath("clang++").map(path -> new DetectedCompiler(CompilerKind.CLANG, path, "Clang")).ifPresent(compilers::add);
        }
        return compilers;
    }

    private Optional<DetectedCompiler> detectMsvc() {
        Path vswhere = Path.of("C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe");
        if (!Files.isRegularFile(vswhere)) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder(
                    vswhere.toString(),
                    "-latest",
                    "-products",
                    "*",
                    "-requires",
                    "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                    "-find",
                    "VC/Tools/MSVC/**/bin/Hostx64/x64/cl.exe"
            ).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return output.lines()
                        .findFirst()
                        .map(Path::of)
                        .filter(Files::isRegularFile)
                        .map(path -> new DetectedCompiler(CompilerKind.MSVC, path, "MSVC"));
            }
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    private Optional<Path> findOnPath(String executableName) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String windowsExecutable = executableName.endsWith(".exe") ? executableName : executableName + ".exe";
        for (String entry : path.split(java.io.File.pathSeparator)) {
            Path directory = Path.of(entry);
            Path candidate = directory.resolve(executableName);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            Path windowsCandidate = directory.resolve(windowsExecutable);
            if (Files.isRegularFile(windowsCandidate)) {
                return Optional.of(windowsCandidate);
            }
        }
        return Optional.empty();
    }

    private Optional<Path> findExecutable(String executableName, List<Path> fallbackPaths) {
        Optional<Path> pathCompiler = findOnPath(executableName);
        if (pathCompiler.isPresent()) {
            return pathCompiler;
        }
        return fallbackPaths.stream()
                .filter(Files::isRegularFile)
                .findFirst();
    }
}


