package dev.frost.graph;

/** Receives bounded graph generation progress without tying builders to a UI toolkit. */
@FunctionalInterface
public interface GraphProgressListener {
    GraphProgressListener NONE = (completed, total, message) -> { };

    void onProgress(long completed, long total, String message);
}
