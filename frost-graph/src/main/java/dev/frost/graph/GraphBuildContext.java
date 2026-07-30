package dev.frost.graph;

/** Shared options, cancellation, progress, and cache context passed to graph builders. */
public record GraphBuildContext(GraphOptions options, GraphCancellation cancellation,
                                GraphProgressListener progress, GraphCache cache) {
    public GraphBuildContext {
        options = options == null ? GraphOptions.defaults() : options;
        cancellation = cancellation == null ? GraphCancellation.NONE : cancellation;
        progress = progress == null ? GraphProgressListener.NONE : progress;
        cache = cache == null ? new GraphCache() : cache;
    }

    public static GraphBuildContext defaults() {
        return new GraphBuildContext(GraphOptions.defaults(), GraphCancellation.NONE,
                GraphProgressListener.NONE, new GraphCache());
    }
}
