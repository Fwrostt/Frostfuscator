package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;

public class FlowRangeTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "flow-range";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 35), 0, 100);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            int changed = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if (AccessHelper.isInitializer(method)) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                if (RANDOM.nextInt(100) < probability && wrapRange(method)) {
                    changed++;
                }
            }

            if (changed > 0) {
                pool.markDirty(classNode.name);
                log("Wrapped {} methods in synthetic exception ranges in {}", changed, classNode.name);
            }
        }
    }

    private boolean wrapRange(MethodNode method) {
        AbstractInsnNode first = firstExecutable(method);
        AbstractInsnNode last = lastExecutable(method);
        if (first == null || last == null || first == last) return false;

        LabelNode start = new LabelNode(new Label());
        LabelNode end = new LabelNode(new Label());
        LabelNode handler = new LabelNode(new Label());

        method.instructions.insertBefore(first, start);
        method.instructions.insert(last, end);
        InsnList handlerCode = new InsnList();
        handlerCode.add(handler);
        handlerCode.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(handlerCode);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        return true;
    }

    private AbstractInsnNode firstExecutable(MethodNode method) {
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode)
                    && !(insn instanceof FrameNode)) {
                return insn;
            }
            insn = insn.getNext();
        }
        return null;
    }

    private AbstractInsnNode lastExecutable(MethodNode method) {
        AbstractInsnNode insn = method.instructions.getLast();
        while (insn != null) {
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode)
                    && !(insn instanceof FrameNode)) {
                return insn;
            }
            insn = insn.getPrevious();
        }
        return null;
    }

    private int getIntOption(TransformerConfig config, String key, int defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
