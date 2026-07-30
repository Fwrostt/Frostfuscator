package dev.frost.graph;

import java.util.concurrent.CancellationException;

/** Cancellation boundary used by all potentially expensive graph builders. */
@FunctionalInterface
public interface GraphCancellation {
    GraphCancellation NONE = () -> false;

    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) throw new CancellationException("Graph generation was cancelled");
    }
}
