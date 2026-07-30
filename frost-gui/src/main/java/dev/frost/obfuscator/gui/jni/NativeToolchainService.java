package dev.frost.obfuscator.gui.jni;

import dev.frost.obfuscator.jni.compiler.CompilerDetector;
import dev.frost.obfuscator.jni.compiler.DetectedCompiler;
import dev.frost.obfuscator.jni.compiler.TargetPlatform;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Performs bounded local compiler discovery without blocking JavaFX rendering. */
public final class NativeToolchainService implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "frost-toolchain-detection");
        thread.setDaemon(true);
        return thread;
    });

    public CompletableFuture<List<DetectedCompiler>> detect() {
        return CompletableFuture.supplyAsync(() -> new CompilerDetector().detectAll(TargetPlatform.current()), executor);
    }

    @Override public void close() { executor.shutdownNow(); }
}
