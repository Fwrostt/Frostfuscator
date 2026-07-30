package dev.frost.graph.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.frost.graph.Graph;

/** Deterministic JSON serialization for neutral graphs. */
public final class JsonGraphExporter implements GraphExporter {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public String format() {
        return "json";
    }

    @Override
    public String fileExtension() {
        return "json";
    }

    @Override
    public String mediaType() {
        return "application/json";
    }

    @Override
    public String export(Graph graph) {
        return gson.toJson(graph);
    }
}
