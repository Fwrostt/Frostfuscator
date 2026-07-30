package dev.frost.graph.bytecode;

import java.util.Objects;

/** Selects one method for a control-flow graph. Descriptor may be omitted only when unambiguous. */
public record ControlFlowRequest(BytecodeProject project, String className, String methodName, String descriptor) {
    public ControlFlowRequest {
        project = Objects.requireNonNull(project, "project");
        className = normalize(Objects.requireNonNull(className, "className"));
        methodName = Objects.requireNonNull(methodName, "methodName");
        descriptor = descriptor == null || descriptor.isBlank() ? null : descriptor;
    }
    private static String normalize(String value) { return value.replace('.', '/'); }
}
