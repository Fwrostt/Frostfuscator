package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.transformer.phase5.SsaCopyWeavingPass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.concurrent.atomic.LongAdder;

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
        transformInternal(pool, config, null);
    }

    @Override
    public void transform(Context context) {
        transformInternal(context.pool(), context.config(), context);
    }

    private void transformInternal(ClassPool pool, TransformerConfig config, Context context) {
        int probability = clamp(getIntOption(config, "probability", 8), 0, 100);
        int maxPerMethod = Math.max(0, getIntOption(config, "max-per-method", 16));
        LongAdder ssaCopies = new LongAdder();
        LongAdder asmSequences = new LongAdder();
        LongAdder ssaFallbacks = new LongAdder();
        IrMethodPassAdapter irAdapter = new IrMethodPassAdapter();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                return;
            }

            int changed = 0;
            boolean framesDirty = false;
            for (int methodIndex = 0; methodIndex < classNode.methods.size(); methodIndex++) {
                MethodNode method = classNode.methods.get(methodIndex);
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                SsaCopyWeavingPass pass = new SsaCopyWeavingPass(
                        "phase5.stack-register-weaving", probability, maxPerMethod);
                IrMethodPassAdapter.Result result = irAdapter.run(classNode.name, method, pass,
                        RANDOM.nextLong() ^ classNode.name.hashCode() ^ method.name.hashCode());
                if (result.changed()) {
                    classNode.methods.set(methodIndex, result.output().orElseThrow());
                    int copies = Math.toIntExact(result.metric("copies"));
                    changed += copies;
                    ssaCopies.add(copies);
                    framesDirty = true;
                    continue;
                }
                if (result.status() != IrMethodPassAdapter.Status.UNCHANGED) ssaFallbacks.increment();

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
                asmSequences.add(inserted);
            }

            if (changed > 0) {
                if (framesDirty) pool.markFramesDirty(classNode.name);
                else pool.markDirty(classNode.name);
                detail("Inserted {} stack manipulation sequences in {}", changed, classNode.name);
            }
        });
        if (context != null) {
            context.stats().add("stackManipulationSsaRegisterCopies", ssaCopies.sum());
            context.stats().add("stackManipulationAsmFallbackSequences", asmSequences.sum());
            context.stats().add("stackManipulationSsaFallbackMethods", ssaFallbacks.sum());
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
