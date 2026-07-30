package dev.frost.graph.bytecode;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.Map;

public final class InheritanceGraphBuilder implements GraphBuilder<BytecodeProject> {
    @Override public Graph build(BytecodeProject project, GraphBuildContext context) {
        String key = "inheritance:" + project.fingerprint() + ":" + context.options();
        var cached = context.cache().get(key); if (cached.isPresent()) return cached.get();
        GraphCollector out = new GraphCollector(key, "Inheritance and interfaces", GraphType.INHERITANCE, context.options());
        Map<String, ClassReferences> classes = BytecodeGraphs.scan(project, context);
        classes.keySet().forEach(name -> out.addNode(BytecodeGraphs.classNode(project, name, false)));
        for (var entry : classes.entrySet()) {
            add(out, project, classes, entry.getKey(), entry.getValue().superName, EdgeType.EXTENDS, context.options());
            for (String iface : entry.getValue().interfaces) add(out, project, classes, entry.getKey(), iface, EdgeType.IMPLEMENTS, context.options());
        }
        Graph graph = out.build(); context.cache().put(key, graph); return graph;
    }
    private static void add(GraphCollector out, BytecodeProject project, Map<String, ClassReferences> known,
                            String source, String target, EdgeType type, GraphOptions options) {
        if (target == null) return;
        boolean external = !known.containsKey(target);
        if (external && !options.includeLibraries()) return;
        out.addNode(BytecodeGraphs.classNode(project, target, external));
        out.addEdge(new GraphEdge(null, GraphIds.nodeId("class", source), GraphIds.nodeId("class", target), type, "", GraphMetadata.EMPTY));
    }
}
