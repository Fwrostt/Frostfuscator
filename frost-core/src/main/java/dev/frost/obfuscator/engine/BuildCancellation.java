package dev.frost.obfuscator.engine;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared cooperative cancellation signal for the build thread and transformer workers.
 */
public final class BuildCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Frostfuscator build cancelled");
        }
    }
}
