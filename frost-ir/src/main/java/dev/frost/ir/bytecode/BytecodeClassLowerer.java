package dev.frost.ir.bytecode;

import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.core.SourcePosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Rebuilds stack maps transactionally and preserves exact original bytes when no method changed. */
public final class BytecodeClassLowerer {
    public BytecodeClassLoweringResult lower(BytecodeClassImportResult imported) {
        return lower(imported, ClassHierarchyResolver.conservative());
    }

    public BytecodeClassLoweringResult lower(BytecodeClassImportResult imported, ClassHierarchyResolver hierarchy) {
        Objects.requireNonNull(imported, "imported");
        Objects.requireNonNull(hierarchy, "hierarchy");
        if (!imported.changed() && imported.exactOriginalBytes().isPresent()) {
            return new BytecodeClassLoweringResult(imported.exactOriginalBytes().orElseThrow(), List.of(), true);
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        ClassNode output = BytecodeClassImporter.cloneClass(imported.preservedClass());
        List<Map.Entry<MethodIdentity, BytecodeImportResult>> methods = imported.methods().entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().index())).toList();
        for (Map.Entry<MethodIdentity, BytecodeImportResult> entry : methods) {
            BytecodeLoweringResult lowered = new BytecodeMethodLowerer().lower(entry.getValue().method(), entry.getValue());
            diagnostics.addAll(lowered.diagnostics());
            if (!lowered.succeeded()) return new BytecodeClassLoweringResult(null, diagnostics, false);
            output.methods.set(entry.getKey().index(), lowered.output().orElseThrow());
        }
        try {
            ClassWriter writer = new HierarchyWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, hierarchy);
            output.accept(writer);
            byte[] bytes = writer.toByteArray();
            verify(bytes);
            return new BytecodeClassLoweringResult(bytes, diagnostics, false);
        } catch (RuntimeException exception) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "class-lowering.write",
                    "Class writing or verification failed: " + exception.getMessage(), null,
                    SourcePosition.UNKNOWN, Map.of("exception", exception.getClass().getName())));
            return new BytecodeClassLoweringResult(null, diagnostics, false);
        }
    }

    private void verify(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException exception) {
                throw new IllegalStateException(node.name + "." + method.name + method.desc + ": " + exception.getMessage(), exception);
            }
        }
    }

    private static final class HierarchyWriter extends ClassWriter {
        private final ClassHierarchyResolver hierarchy;
        private HierarchyWriter(int flags, ClassHierarchyResolver hierarchy) { super(flags); this.hierarchy = hierarchy; }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return hierarchy.commonSuperClass(type1, type2);
        }
    }
}
