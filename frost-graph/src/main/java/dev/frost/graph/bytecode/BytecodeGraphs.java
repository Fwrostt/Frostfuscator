package dev.frost.graph.bytecode;

import dev.frost.graph.*;
import java.util.*;

final class BytecodeGraphs {
    private BytecodeGraphs() {}

    static Map<String, ClassReferences> scan(BytecodeProject project, GraphBuildContext context) {
        return project.index(context).references();
    }

    static GraphNode classNode(BytecodeProject project, String name, boolean external) {
        boolean library = project.isLibrary(name) || external;
        return new GraphNode(GraphIds.nodeId("class", name), simple(name),
                library ? NodeType.LIBRARY_CLASS : NodeType.CLASS,
                GraphMetadata.builder().put("internalName", name).put("library", library)
                        .put("qualifiedName", name.replace('/', '.')).put("package", packageName(name)).build());
    }

    static String methodId(String owner, String name, String desc) {
        return GraphIds.nodeId("method", owner + "." + name + desc);
    }
    static String simple(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }
    static String packageName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "(default)" : internalName.substring(0, slash).replace('/', '.');
    }
}
