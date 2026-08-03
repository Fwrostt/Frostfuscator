package dev.frost.ir.bytecode;

import dev.frost.ir.core.Diagnostic;
import java.util.List;
import java.util.Optional;
import org.objectweb.asm.tree.MethodNode;

public record BytecodeLoweringResult(MethodNode method, List<Diagnostic> diagnostics) {
    public BytecodeLoweringResult { diagnostics = List.copyOf(diagnostics); }
    public Optional<MethodNode> output() { return Optional.ofNullable(method); }
    public boolean succeeded() { return method != null && diagnostics.stream().noneMatch(value -> value.severity().ordinal() >= 2); }
}
