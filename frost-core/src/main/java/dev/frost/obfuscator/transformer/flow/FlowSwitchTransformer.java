package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class FlowSwitchTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "flow-switch";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 75), 0, 100);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            int changed = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;

                AbstractInsnNode insn = method.instructions.getFirst();
                while (insn != null) {
                    AbstractInsnNode next = insn.getNext();
                    if (RANDOM.nextInt(100) >= probability) {
                        insn = next;
                        continue;
                    }

                    if (insn instanceof TableSwitchInsnNode tableSwitch) {
                        if (rewriteTableSwitch(method, tableSwitch)) changed++;
                    } else if (insn instanceof LookupSwitchInsnNode lookupSwitch) {
                        if (rewriteLookupSwitch(method, lookupSwitch)) changed++;
                    }
                    insn = next;
                }
            }

            if (changed > 0) {
                pool.markDirty(classNode.name);
                log("Hashed {} switch dispatches in {}", changed, classNode.name);
            }
        }
    }

    private boolean rewriteTableSwitch(MethodNode method, TableSwitchInsnNode node) {
        int seed = nonZeroRandom();
        List<SwitchCase> cases = new ArrayList<>();
        for (int i = 0; i < node.labels.size(); i++) {
            cases.add(new SwitchCase((node.min + i) ^ seed, node.labels.get(i)));
        }
        cases.sort(Comparator.comparingInt(c -> c.key));

        int[] keys = cases.stream().mapToInt(c -> c.key).toArray();
        LabelNode[] labels = cases.stream().map(c -> c.label).toArray(LabelNode[]::new);
        InsnList prefix = new InsnList();
        prefix.add(new LdcInsnNode(seed));
        prefix.add(new InsnNode(Opcodes.IXOR));
        method.instructions.insertBefore(node, prefix);
        method.instructions.set(node, new LookupSwitchInsnNode(node.dflt, keys, labels));
        return true;
    }

    private boolean rewriteLookupSwitch(MethodNode method, LookupSwitchInsnNode node) {
        int seed = nonZeroRandom();
        List<SwitchCase> cases = new ArrayList<>();
        for (int i = 0; i < node.keys.size(); i++) {
            cases.add(new SwitchCase(node.keys.get(i) ^ seed, node.labels.get(i)));
        }
        cases.sort(Comparator.comparingInt(c -> c.key));

        int[] keys = cases.stream().mapToInt(c -> c.key).toArray();
        LabelNode[] labels = cases.stream().map(c -> c.label).toArray(LabelNode[]::new);
        InsnList prefix = new InsnList();
        prefix.add(new LdcInsnNode(seed));
        prefix.add(new InsnNode(Opcodes.IXOR));
        method.instructions.insertBefore(node, prefix);
        method.instructions.set(node, new LookupSwitchInsnNode(node.dflt, keys, labels));
        return true;
    }

    private int nonZeroRandom() {
        int value;
        do {
            value = RANDOM.nextInt();
        } while (value == 0);
        return value;
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

    private record SwitchCase(int key, LabelNode label) {
    }
}
