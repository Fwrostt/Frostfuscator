package dev.frost.graph.transform;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.*;

/** Pipeline/config preview with phase ordering and dependency/conflict validation. */
public final class TransformerPipelineGraphBuilder implements GraphBuilder<List<TransformerDescriptor>> {
    @Override public Graph build(List<TransformerDescriptor> source, GraphBuildContext context) {
        List<TransformerDescriptor> plan = source == null ? List.of() : source.stream()
                .filter(TransformerDescriptor::enabled).sorted(Comparator.comparingInt(TransformerDescriptor::priority)
                        .thenComparing(TransformerDescriptor::id)).toList();
        GraphCollector out = new GraphCollector(GraphIds.graphId(GraphType.TRANSFORMER_PIPELINE, plan.toString()),
                "Transformer pipeline", GraphType.TRANSFORMER_PIPELINE, context.options());
        Map<String, TransformerDescriptor> byId = new LinkedHashMap<>();
        for (TransformerDescriptor item : plan) {
            byId.put(item.id(), item);
            out.addNode(node(item));
        }
        for (int i = 1; i < plan.size(); i++) out.addEdge(new GraphEdge(null, id(plan.get(i - 1)), id(plan.get(i)),
                EdgeType.EXECUTES_BEFORE, "", GraphMetadata.EMPTY));
        for (TransformerDescriptor item : plan) {
            for (String required : item.dependencies()) {
                TransformerDescriptor dependency = byId.get(required);
                if (dependency == null) {
                    out.warning(warning("missing-dependency", item.id() + " requires missing transformer " + required));
                    continue;
                }
                out.addEdge(new GraphEdge(null, id(item), id(dependency), EdgeType.REQUIRES, "requires", GraphMetadata.EMPTY));
                if (dependency.priority() > item.priority()) out.warning(warning("dependency-order",
                        item.id() + " runs before required transformer " + required));
            }
            for (String conflict : item.conflicts()) if (byId.containsKey(conflict)) {
                out.addEdge(new GraphEdge(null, id(item), id(byId.get(conflict)), EdgeType.CONFLICTS, "conflicts", GraphMetadata.EMPTY));
                out.warning(warning("transformer-conflict", item.id() + " conflicts with " + conflict));
            }
        }
        detectCycles(plan, byId, out);
        out.metadata(GraphMetadata.builder().put("enabledTransformers", plan.size())
                .put("phases", plan.stream().map(TransformerDescriptor::phase).distinct().toList()).build());
        return out.build();
    }
    private static GraphNode node(TransformerDescriptor item) {
        return new GraphNode(id(item), item.displayName(), NodeType.TRANSFORMER,
                GraphMetadata.builder().put("id", item.id()).put("enabled", item.enabled())
                        .put("priority", item.priority()).put("phase", item.phase())
                        .put("dependencies", item.dependencies()).put("conflicts", item.conflicts())
                        .put("inclusions", item.inclusions()).put("exclusions", item.exclusions())
                        .put("configuration", item.configuration().values()).build());
    }
    private static String id(TransformerDescriptor item) { return GraphIds.nodeId("transformer", item.id()); }
    private static GraphWarning warning(String code, String message) {
        return new GraphWarning(GraphWarning.Severity.WARNING, code, message, GraphMetadata.EMPTY);
    }
    private static void detectCycles(List<TransformerDescriptor> plan, Map<String, TransformerDescriptor> byId, GraphCollector out) {
        Set<String> visited = new HashSet<>(), active = new HashSet<>();
        for (TransformerDescriptor item : plan) visit(item.id(), byId, visited, active, new ArrayDeque<>(), out);
    }
    private static void visit(String id, Map<String, TransformerDescriptor> byId, Set<String> visited,
                              Set<String> active, Deque<String> path, GraphCollector out) {
        if (active.contains(id)) {
            List<String> cycle = new ArrayList<>(path); cycle.add(id);
            out.warning(warning("dependency-cycle", "Transformer dependency cycle: " + String.join(" -> ", cycle)));
            return;
        }
        if (!visited.add(id)) return;
        active.add(id); path.addLast(id);
        TransformerDescriptor descriptor = byId.get(id);
        if (descriptor != null) for (String dependency : descriptor.dependencies()) if (byId.containsKey(dependency))
            visit(dependency, byId, visited, active, path, out);
        path.removeLast(); active.remove(id);
    }
}
