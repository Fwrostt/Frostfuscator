package dev.frost.ir.bytecode;

import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.core.IrContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Class-level lossless shell: fields, records, modules, annotations, attributes and method order remain in ASM. */
public final class BytecodeClassImporter {
    private final IrContext context;

    public BytecodeClassImporter(IrContext context) { this.context = Objects.requireNonNull(context, "context"); }

    public BytecodeClassImportResult importClass(byte[] classFile) {
        Objects.requireNonNull(classFile, "classFile");
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(classFile).accept(node, ClassReader.EXPAND_FRAMES);
        return importClass(node, classFile);
    }

    public BytecodeClassImportResult importClass(ClassNode source) { return importClass(source, null); }

    private BytecodeClassImportResult importClass(ClassNode source, byte[] originalBytes) {
        Objects.requireNonNull(source, "source");
        ClassNode preserved = cloneClass(source);
        Map<MethodIdentity, BytecodeImportResult> methods = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (int index = 0; index < source.methods.size(); index++) {
            MethodNode method = source.methods.get(index);
            BytecodeImportResult imported = new BytecodeSsaImporter(context).importMethod(source.name, method);
            methods.put(new MethodIdentity(index, method.name, method.desc), imported);
            diagnostics.addAll(imported.diagnostics());
        }
        return new BytecodeClassImportResult(source.name, preserved, originalBytes, methods, diagnostics);
    }

    static ClassNode cloneClass(ClassNode source) {
        ClassNode copy = new ClassNode(Opcodes.ASM9);
        source.accept(copy);
        return copy;
    }
}
