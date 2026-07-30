package dev.frost.graph.transform;

import dev.frost.graph.bytecode.BytecodeClassInfo;

import java.util.List;

/** Inputs for a non-mutating, class-scoped obfuscation applicability preview. */
public record ObfuscationPreviewRequest(BytecodeClassInfo target,
                                        List<TransformerDescriptor> transformers,
                                        List<String> globalInclusions,
                                        List<String> globalExclusions) {
    public ObfuscationPreviewRequest {
        transformers = transformers == null ? List.of() : List.copyOf(transformers);
        globalInclusions = globalInclusions == null ? List.of() : List.copyOf(globalInclusions);
        globalExclusions = globalExclusions == null ? List.of() : List.copyOf(globalExclusions);
    }
}
