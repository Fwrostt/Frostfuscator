package dev.frost.obfuscator.jni.compiler;

import dev.frost.obfuscator.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ProcessRunner {
    private static final long HEARTBEAT_SECONDS = 15L;

    ProcessResult run(CompilerCommand command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command.command());
        builder.directory(command.workingDirectory().toFile());
        builder.environment().putAll(command.environment());
        Process process = builder.start();
        ExecutorService streamReaders = Executors.newFixedThreadPool(2);
        Future<String> stdout = streamReaders.submit(() -> readText(process.getInputStream()));
        Future<String> stderr = streamReaders.submit(() -> readText(process.getErrorStream()));
        long started = System.nanoTime();
        try {
            while (!process.waitFor(HEARTBEAT_SECONDS, TimeUnit.SECONDS)) {
                long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
                Logger.info("[COMPILER] Still compiling after {}s", elapsed);
            }
            return new ProcessResult(
                    process.exitValue(),
                    await(stdout),
                    await(stderr)
            );
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            throw exception;
        } finally {
            streamReaders.shutdownNow();
        }
    }

    private String readText(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String await(Future<String> future) throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to read compiler output", cause);
        }
    }
}


