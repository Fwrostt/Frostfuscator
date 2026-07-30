package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.List;

public final class ConfigurationPreviewGraphBuilder implements GraphBuilder<List<TransformerDescriptor>> {
    @Override public Graph build(List<TransformerDescriptor> source, GraphBuildContext context) {
        Graph pipeline = new TransformerPipelineGraphBuilder().build(source, context);
        return new Graph(pipeline.id() + "-preview", "Configuration preview", GraphType.CONFIGURATION_PREVIEW,
                pipeline.nodes(), pipeline.edges(), pipeline.metadata(), pipeline.warnings(), pipeline.truncated());
    }
}
