package dev.frost.ir.bytecode;

import dev.frost.ir.core.Diagnostic;
import java.util.List;
import java.util.Optional;

public record BytecodeClassLoweringResult(byte[] classFile, List<Diagnostic> diagnostics,
                                          boolean exactOriginal) {
    public BytecodeClassLoweringResult {
        classFile = classFile == null ? null : classFile.clone();
        diagnostics = List.copyOf(diagnostics);
    }
    @Override public byte[] classFile() { return classFile == null ? null : classFile.clone(); }
    public Optional<byte[]> output() { return classFile == null ? Optional.empty() : Optional.of(classFile.clone()); }
    public boolean succeeded() { return classFile != null && diagnostics.stream().noneMatch(value -> value.severity().ordinal() >= 2); }
}
