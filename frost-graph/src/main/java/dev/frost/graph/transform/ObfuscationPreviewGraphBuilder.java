package dev.frost.graph.transform;

import dev.frost.graph.EdgeType;
import dev.frost.graph.Graph;
import dev.frost.graph.GraphBuildContext;
import dev.frost.graph.GraphCollector;
import dev.frost.graph.GraphEdge;
import dev.frost.graph.GraphIds;
import dev.frost.graph.GraphMetadata;
import dev.frost.graph.GraphNode;
import dev.frost.graph.GraphType;
import dev.frost.graph.NodeType;
import dev.frost.graph.builder.GraphBuilder;

import java.util.List;
import java.util.regex.Pattern;

/** Builds a safe dry-run view of which enabled transformers can inspect a selected class. */
public final class ObfuscationPreviewGraphBuilder implements GraphBuilder<ObfuscationPreviewRequest> {
    @Override
    public Graph build(ObfuscationPreviewRequest request, GraphBuildContext context) {
        if (request.target() == null) throw new IllegalArgumentException("Choose a class for the dry-run preview");
        String className = request.target().internalName();
        GraphCollector out = new GraphCollector(GraphIds.graphId(GraphType.OBFUSCATION_PREVIEW, className),
                "Obfuscation dry run", GraphType.OBFUSCATION_PREVIEW, context.options());
        String classId = GraphIds.nodeId("class", className);
        out.addNode(new GraphNode(classId, request.target().displayName(), NodeType.CLASS,
                GraphMetadata.builder().put("internalName", className)
                        .put("qualifiedName", request.target().qualifiedName())
                        .put("package", request.target().packageName())
                        .put("methods", request.target().methods().size()).build()));

        int applicable = 0;
        int excluded = 0;
        for (TransformerDescriptor transformer : request.transformers()) {
            context.cancellation().throwIfCancelled();
            Decision decision = decision(className, request.globalInclusions(), request.globalExclusions(),
                    transformer.inclusions(), transformer.exclusions());
            if (!decision.applicable()) {
                excluded++;
                continue;
            }
            applicable++;
            String transformerId = GraphIds.nodeId("transformer", transformer.id());
            out.addNode(new GraphNode(transformerId, transformer.displayName(), NodeType.TRANSFORMER,
                    GraphMetadata.builder().put("id", transformer.id()).put("phase", transformer.phase())
                            .put("priority", transformer.priority()).put("reason", decision.reason())
                            .put("configuration", transformer.configuration().values()).build()));
            out.addEdge(new GraphEdge(null, classId, transformerId, EdgeType.MODIFIES, "would inspect",
                    GraphMetadata.builder().put("dryRun", true).put("reason", decision.reason()).build()));
        }
        out.metadata(GraphMetadata.builder().put("mode", "dry-run").put("class", className)
                .put("applicableTransformers", applicable).put("excludedTransformers", excluded)
                .put("mutated", false).build());
        return out.build();
    }

    private static Decision decision(String internalName, List<String> globalIncludes, List<String> globalExcludes,
                                     List<String> includes, List<String> excludes) {
        String dotted = internalName.replace('/', '.');
        if (matchesAny(dotted, globalExcludes) || matchesAny(internalName, globalExcludes))
            return new Decision(false, "global exclusion");
        if (matchesAny(dotted, excludes) || matchesAny(internalName, excludes))
            return new Decision(false, "transformer exclusion");
        if (!globalIncludes.isEmpty() && !matchesAny(dotted, globalIncludes) && !matchesAny(internalName, globalIncludes))
            return new Decision(false, "outside global inclusions");
        if (!includes.isEmpty() && !matchesAny(dotted, includes) && !matchesAny(internalName, includes))
            return new Decision(false, "outside transformer inclusions");
        String reason = includes.isEmpty() && globalIncludes.isEmpty() ? "enabled for this class" : "matched inclusion rules";
        return new Decision(true, reason);
    }

    private static boolean matchesAny(String value, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) continue;
            try {
                if (Pattern.matches(pattern, value)) return true;
            } catch (RuntimeException ignored) {
                if (value.equals(pattern) || value.contains(pattern)) return true;
            }
        }
        return false;
    }

    private record Decision(boolean applicable, String reason) { }
}
