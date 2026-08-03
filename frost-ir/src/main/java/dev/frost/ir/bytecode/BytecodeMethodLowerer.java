package dev.frost.ir.bytecode;

import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.core.SourcePosition;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.verify.IrValidator;
import dev.frost.ir.verify.ValidationProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transactional lowering entry point. It never mutates or reuses the caller's ASM MethodNode. */
public final class BytecodeMethodLowerer {
    public BytecodeLoweringResult lower(IrMethod method, BytecodeImportResult origin) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(origin, "origin");
        if (origin.method() != method) throw new IllegalArgumentException("Import origin belongs to a different IR method");
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (method.revision() == origin.importedRevision()) {
            return new BytecodeLoweringResult(AsmMethodCloner.clone(origin.preservedSnapshot()), diagnostics);
        }
        if (!origin.has(ImportCapability.TYPED_STACK_SSA)) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "lowering.requires-ssa",
                    "Mutation-aware lowering requires a complete typed SSA import", null,
                    SourcePosition.UNKNOWN, Map.of()));
            return new BytecodeLoweringResult(null, diagnostics);
        }
        diagnostics.addAll(new IrValidator().validate(method, ValidationProfile.LOWERABLE).diagnostics());
        if (diagnostics.stream().noneMatch(value -> value.severity().ordinal() >= 2)) {
            return new SsaBytecodeEmitter(method, origin).emit();
        }
        return new BytecodeLoweringResult(null, diagnostics);
    }
}
