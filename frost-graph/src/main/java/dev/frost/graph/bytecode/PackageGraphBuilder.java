package dev.frost.graph.bytecode;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.*;

public final class PackageGraphBuilder implements GraphBuilder<BytecodeProject> {
    @Override public Graph build(BytecodeProject project, GraphBuildContext context) {
        String key = "packages:" + project.fingerprint() + ":" + context.options();
        var cached = context.cache().get(key); if (cached.isPresent()) return cached.get();
        GraphCollector out = new GraphCollector(key, "Package dependencies", GraphType.PACKAGE_DEPENDENCY, context.options());
        Map<String, ClassReferences> classes = BytecodeGraphs.scan(project, context);
        Map<String, Integer> counts = new TreeMap<>();
        for (String name : classes.keySet()) counts.merge(BytecodeGraphs.packageName(name), 1, Integer::sum);
        counts.forEach((pkg, count) -> out.addNode(new GraphNode(GraphIds.nodeId("package", pkg), pkg, NodeType.PACKAGE,
                GraphMetadata.builder().put("classCount", count).build())));
        Map<String, Integer> weights = new TreeMap<>();
        for (var entry : classes.entrySet()) for (String dependency : entry.getValue().dependencies) {
            if (!classes.containsKey(dependency) && !context.options().includeLibraries()) continue;
            String from = BytecodeGraphs.packageName(entry.getKey());
            String to = BytecodeGraphs.packageName(dependency);
            if (from.equals(to)) continue;
            if (!counts.containsKey(to)) out.addNode(new GraphNode(GraphIds.nodeId("package", to), to, NodeType.PACKAGE,
                    GraphMetadata.builder().put("library", true).build()));
            weights.merge(from + "\u0000" + to, 1, Integer::sum);
        }
        weights.forEach((pair, weight) -> {
            String[] parts = pair.split("\u0000", 2);
            out.addEdge(new GraphEdge(null, GraphIds.nodeId("package", parts[0]), GraphIds.nodeId("package", parts[1]),
                    EdgeType.PACKAGE_DEPENDENCY, Integer.toString(weight), GraphMetadata.builder().put("references", weight).build()));
        });
        Graph graph = out.build(); context.cache().put(key, graph); return graph;
    }
}
