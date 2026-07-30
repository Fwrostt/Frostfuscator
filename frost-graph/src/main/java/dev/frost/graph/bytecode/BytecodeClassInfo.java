package dev.frost.graph.bytecode;

import java.util.List;

/** Searchable class summary exposed by the bytecode graph index. */
public record BytecodeClassInfo(String internalName, String displayName, String packageName,
                                boolean library, List<BytecodeMethodInfo> methods) {
    public BytecodeClassInfo {
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    public String qualifiedName() {
        return internalName.replace('/', '.');
    }
}
