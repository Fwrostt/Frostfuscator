package dev.frost.obfuscator.transformer.optimization;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import java.util.concurrent.atomic.LongAdder;

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
        LongAdder lineNumbers = new LongAdder();
        LongAdder locals = new LongAdder();

        context.pool().forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())) {
                return;
            }
            classNode.sourceFile = null;
            classNode.sourceDebug = null;
            for (MethodNode method : classNode.methods) {
                if (method.localVariables != null) {
                    locals.add(method.localVariables.size());
                    method.localVariables = null;
                }
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn instanceof LineNumberNode) {
                        method.instructions.remove(insn);
                        lineNumbers.increment();
                    }
                    insn = next;
                }
            }
            context.pool().markDirty(classNode.name);
        });

        context.stats().add("lineNumbersRemoved", lineNumbers.sum());
        context.stats().add("localVariablesRemoved", locals.sum());
        log("Removed {} line numbers and {} local variable entries", lineNumbers.sum(), locals.sum());
    }
}
