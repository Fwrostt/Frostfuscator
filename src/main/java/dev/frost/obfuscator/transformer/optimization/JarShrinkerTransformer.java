package dev.frost.obfuscator.transformer.optimization;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

public class JarShrinkerTransformer extends Transformer {

    @Override
    public String getName() {
        return "jar-shrinker";
    }

    @Override
    public String getCategory() {
        return "Optimization";
    }

    @Override
    public void transform(Context context) {
        int lineNumbers = 0;
        int locals = 0;

        for (ClassNode classNode : context.pool().getClasses()) {
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())) {
                continue;
            }
            classNode.sourceFile = null;
            classNode.sourceDebug = null;
            for (MethodNode method : classNode.methods) {
                if (method.localVariables != null) {
                    locals += method.localVariables.size();
                    method.localVariables = null;
                }
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn instanceof LineNumberNode) {
                        method.instructions.remove(insn);
                        lineNumbers++;
                    }
                    insn = next;
                }
            }
            context.pool().markDirty(classNode.name);
        }

        context.stats().add("lineNumbersRemoved", lineNumbers);
        context.stats().add("localVariablesRemoved", locals);
        log("Removed {} line numbers and {} local variable entries", lineNumbers, locals);
    }
}
