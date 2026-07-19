package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;

public class StackManipulationTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "stack-manipulation";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 8), 0, 100);
        int maxPerMethod = Math.max(0, getIntOption(config, "max-per-method", 16));

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            int changed = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                int inserted = 0;
                AbstractInsnNode insn = method.instructions.getFirst();
                while (insn != null && inserted < maxPerMethod) {
                    AbstractInsnNode next = insn.getNext();
                    if (isSafeAnchor(insn) && RANDOM.nextInt(100) < probability) {
                        method.instructions.insertBefore(insn, stackNoise());
                        inserted++;
                    }
                    insn = next;
                }
                changed += inserted;
            }

            if (changed > 0) {
                pool.markDirty(classNode.name);
                log("Inserted {} stack manipulation sequences in {}", changed, classNode.name);
            }
        }
    }

    private boolean isSafeAnchor(AbstractInsnNode insn) {
        return !(insn instanceof LabelNode)
                && !(insn instanceof LineNumberNode)
                && !(insn instanceof FrameNode);
    }

    private InsnList stackNoise() {
        InsnList list = new InsnList();
        switch (RANDOM.nextInt(4)) {
            case 0 -> {
                list.add(new LdcInsnNode(RANDOM.nextInt()));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(Opcodes.POP));
                list.add(new InsnNode(Opcodes.POP));
            }
            case 1 -> {
                list.add(new InsnNode(Opcodes.ACONST_NULL));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(Opcodes.POP));
                list.add(new InsnNode(Opcodes.POP));
            }
            case 2 -> {
                list.add(new InsnNode(Opcodes.ICONST_0));
                list.add(new InsnNode(Opcodes.ICONST_1));
                list.add(new InsnNode(Opcodes.IXOR));
                list.add(new InsnNode(Opcodes.POP));
            }
            default -> {
                list.add(new LdcInsnNode(RANDOM.nextLong()));
                list.add(new InsnNode(Opcodes.DUP2));
                list.add(new InsnNode(Opcodes.POP2));
                list.add(new InsnNode(Opcodes.POP2));
            }
        }
        return list;
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
