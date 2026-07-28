package dev.frost.obfuscator.gui.crypto;

import dev.frost.obfuscator.crypto.PasswordFileCipher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleConsumer;

public final class FileEncryptionService implements AutoCloseable {
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "frost-file-crypto");
        thread.setDaemon(true);
        return thread;
    });

    public CompletableFuture<OperationResult> encrypt(Path input, Path output, char[] password) {
        return encrypt(input, output, password, ignored -> { });
    }

    public CompletableFuture<OperationResult> encrypt(Path input, Path output, char[] password,
                                                      DoubleConsumer progress) {
        return submit(input, output, password, true, progress);
    }

    public CompletableFuture<OperationResult> decrypt(Path input, Path output, char[] password) {
        return decrypt(input, output, password, ignored -> { });
    }

    public CompletableFuture<OperationResult> decrypt(Path input, Path output, char[] password,
                                                      DoubleConsumer progress) {
        return submit(input, output, password, false, progress);
    }

    public CompletableFuture<Boolean> isEncrypted(Path input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return PasswordFileCipher.isEncrypted(input);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, executor);
    }

    private CompletableFuture<OperationResult> submit(Path input, Path output, char[] password, boolean encrypt,
                                                      DoubleConsumer progress) {
        char[] passwordCopy = password == null ? null : password.clone();
        DoubleConsumer progressConsumer = progress == null ? ignored -> { } : progress;
        return CompletableFuture.supplyAsync(() -> {
            Instant started = Instant.now();
            try {
                long inputBytes = Files.size(input);
                PasswordFileCipher.ProgressListener listener = (processed, total) ->
                        progressConsumer.accept(total <= 0 ? 1.0 : Math.min(1.0, (double) processed / total));
                if (encrypt) PasswordFileCipher.encrypt(input, output, passwordCopy, listener);
                else PasswordFileCipher.decrypt(input, output, passwordCopy, listener);
                return new OperationResult(output.toAbsolutePath().normalize(), inputBytes, Files.size(output),
                        Duration.between(started, Instant.now()), encrypt);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            } finally {
                if (passwordCopy != null) Arrays.fill(passwordCopy, '\0');
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public record OperationResult(Path output, long inputBytes, long outputBytes,
                                  Duration elapsed, boolean encrypted) {
    }
}
