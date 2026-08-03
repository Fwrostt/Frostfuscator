package dev.frost.ir.bytecode;

import dev.frost.ir.core.Diagnostic;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.tree.ClassNode;

public record BytecodeClassImportResult(String internalName, ClassNode preservedClass,
                                        byte[] originalBytes, Map<MethodIdentity, BytecodeImportResult> methods,
                                        List<Diagnostic> diagnostics) {
    public BytecodeClassImportResult {
        methods = Map.copyOf(new LinkedHashMap<>(methods));
        diagnostics = List.copyOf(diagnostics);
        originalBytes = originalBytes == null ? null : originalBytes.clone();
    }

    @Override public byte[] originalBytes() { return originalBytes == null ? null : originalBytes.clone(); }
    public Optional<byte[]> exactOriginalBytes() { return originalBytes == null ? Optional.empty() : Optional.of(originalBytes.clone()); }
    public boolean changed() { return methods.values().stream().anyMatch(method -> method.method().revision() != method.importedRevision()); }
}
