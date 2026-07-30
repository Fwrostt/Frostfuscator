package dev.frost.graph.bytecode;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import java.util.Map;

public final class MethodCallGraphBuilder implements GraphBuilder<BytecodeProject> {
    @Override public Graph build(BytecodeProject project, GraphBuildContext context) {
        String key = "calls:" + project.fingerprint() + ":" + context.options();
        var cached = context.cache().get(key); if (cached.isPresent()) return cached.get();
        Map<String, ClassReferences> classes = BytecodeGraphs.scan(project, context);
        String focusOwner = classFocus(context.options().focusNode(), classes);
        GraphCollector out = new GraphCollector(key, focusOwner == null ? "Method calls" : "Class call flow",
                GraphType.METHOD_CALL, context.options());
        for (ClassReferences refs : classes.values()) for (ClassReferences.MethodRef call : refs.calls) {
            boolean external = !classes.containsKey(call.owner());
            if (external && !context.options().includeLibraries()) continue;
            String callerId = BytecodeGraphs.methodId(call.callerOwner(), call.callerName(), call.callerDescriptor());
            String targetId = BytecodeGraphs.methodId(call.owner(), call.name(), call.descriptor());
            out.addNode(methodNode(callerId, call.callerOwner(), call.callerName(), call.callerDescriptor(), false,
                    call.callerOwner().equals(focusOwner)));
            out.addNode(methodNode(targetId, call.owner(), call.name(), call.descriptor(), external || project.isLibrary(call.owner()),
                    call.owner().equals(focusOwner)));
            String flow = flow(call, focusOwner);
            out.addEdge(new GraphEdge(null, callerId, targetId, EdgeType.CALLS, call.kind(),
                    GraphMetadata.builder().put("invocationKind", call.kind()).put("flow", flow).build()));
        }
        Graph graph = out.build(); context.cache().put(key, graph); return graph;
    }
    private static GraphNode methodNode(String id, String owner, String name, String descriptor, boolean library,
                                        boolean focus) {
        return new GraphNode(id, BytecodeGraphs.simple(owner) + "." + name,
                NodeType.METHOD, GraphMetadata.builder().put("owner", owner).put("name", name)
                .put("descriptor", descriptor).put("qualifiedName", owner + "." + name + descriptor)
                .put("library", library).put("focus", focus).build());
    }

    private static String classFocus(String requested, Map<String, ClassReferences> classes) {
        if (requested == null || requested.isBlank()) return null;
        String normalized = requested.replace('.', '/');
        return classes.containsKey(normalized) ? normalized : null;
    }

    private static String flow(ClassReferences.MethodRef call, String focusOwner) {
        if (focusOwner == null) return null;
        boolean fromFocus = call.callerOwner().equals(focusOwner);
        boolean intoFocus = call.owner().equals(focusOwner);
        if (fromFocus && !intoFocus) return "outgoing";
        if (intoFocus && !fromFocus) return "incoming";
        return fromFocus ? "internal" : null;
    }
}
