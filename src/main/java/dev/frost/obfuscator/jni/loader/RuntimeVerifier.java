package dev.frost.obfuscator.jni.loader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes original and protected jars and compares their observable process
 * results.
 */
public final class RuntimeVerifier {
    public VerificationResult verify(Path originalJar, Path protectedJar, String mainClass, List<String> args) throws IOException, InterruptedException {
        ProcessResult original = run(originalJar, mainClass, args);
        ProcessResult protectedResult = run(protectedJar, mainClass, args);
        return new VerificationResult(original, protectedResult, original.equals(protectedResult));
    }

    private ProcessResult run(Path jar, String mainClass, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home")).resolve("bin").resolve("java").toString());
        command.add("-cp");
        command.add(jar.toString());
        command.add(mainClass);
        command.addAll(args);
        Process process = new ProcessBuilder(command).start();
        boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IOException("Timed out running " + jar);
        }
        return new ProcessResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
        );
    }

    public record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    public record VerificationResult(ProcessResult original, ProcessResult protectedResult, boolean identical) {
    }
}


