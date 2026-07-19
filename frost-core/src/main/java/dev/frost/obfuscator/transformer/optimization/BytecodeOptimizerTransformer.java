package dev.frost.obfuscator.transformer.optimization;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class BytecodeOptimizerTransformer extends Transformer {

    @Override
    public String getName() {
        return "bytecode-optimizer";
    }

    @Override
    public String getCategory() {
        return "Optimization";
    }

    @Override
    public void transform(Context context) {
        int removed = 0;
        for (ClassNode classNode : context.pool().getClasses()) {
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())) {
                continue;
            }
            boolean classChanged = false;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn.getOpcode() == Opcodes.NOP) {
                        method.instructions.remove(insn);
                        removed++;
                        classChanged = true;
                    }
                    insn = next;
                }
            }
            if (classChanged) {
                context.pool().markDirty(classNode.name);
            }
        }
        context.stats().add("nopInstructionsRemoved", removed);
        log("Removed {} NOP instructions", removed);
    }
}
