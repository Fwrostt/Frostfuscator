package dev.frost.ir.bytecode;

import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.model.IrMethod;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.MethodNode;

public record BytecodeImportResult(IrMethod method, AsmSourceMap sourceMap, MethodNode preservedSnapshot,
                                   long importedRevision, Set<ImportCapability> capabilities,
                                   List<Diagnostic> diagnostics, FrameStateMap frameStates) {
    public BytecodeImportResult {
        capabilities = capabilities.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean has(ImportCapability capability) { return capabilities.contains(capability); }
    public java.util.Optional<FrameStateMap> frames() { return java.util.Optional.ofNullable(frameStates); }
}
