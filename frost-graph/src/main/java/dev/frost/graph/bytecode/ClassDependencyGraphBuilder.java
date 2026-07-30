package dev.frost.graph.bytecode;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;

import java.util.Map;

public final class ClassDependencyGraphBuilder implements GraphBuilder<BytecodeProject> {
    @Override public Graph build(BytecodeProject project, GraphBuildContext context) {
        String key = "dependencies:" + project.fingerprint() + ":" + context.options();
        var cached = context.cache().get(key); if (cached.isPresent()) return cached.get();
        GraphCollector out = new GraphCollector(key, "Class dependencies", GraphType.CLASS_DEPENDENCY, context.options());
        Map<String, ClassReferences> classes = BytecodeGraphs.scan(project, context);
        classes.keySet().forEach(name -> out.addNode(BytecodeGraphs.classNode(project, name, false)));
        for (var entry : classes.entrySet()) {
            String source = GraphIds.nodeId("class", entry.getKey());
            for (String dependency : entry.getValue().dependencies) {
                if (dependency.equals(entry.getKey())) continue;
                boolean external = !classes.containsKey(dependency);
                if (external && !context.options().includeLibraries()) continue;
                out.addNode(BytecodeGraphs.classNode(project, dependency, external));
                out.addEdge(new GraphEdge(null, source, GraphIds.nodeId("class", dependency),
                        EdgeType.DEPENDS_ON, "", GraphMetadata.EMPTY));
            }
        }
        out.metadata(GraphMetadata.builder().put("fingerprint", project.fingerprint()).put("classes", project.size()).build());
        Graph graph = out.build(); context.cache().put(key, graph); return graph;
    }
}
