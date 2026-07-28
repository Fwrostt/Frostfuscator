package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

public class PolymorphTransformer extends Transformer {

    @Override
    public String getName() {
        return "polymorphic-instruction";
    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        LongAdder polymorphedInstructions = new LongAdder();
        int probability = config.getOptionInt("probability", 60);
        long seed = config.getOptionLong("seed", 12345L);

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                return;
            }

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                Random random = new Random(seed ^ classNode.name.hashCode() ^ method.name.hashCode());
                int count = polymorphMethod(method, probability, random);
                polymorphedInstructions.add(count);
            }
        });

        Logger.info("Applied polymorphic instruction substitution to {} opcode(s)", polymorphedInstructions.sum());
    }

    private int polymorphMethod(MethodNode method, int probability, Random random) {
        InsnList insns = method.instructions;
        AbstractInsnNode[] nodes = insns.toArray();
        int count = 0;

        for (AbstractInsnNode node : nodes) {
            if (random.nextInt(100) >= probability) continue;

            int op = node.getOpcode();
            InsnList replacement = null;

            if (op == Opcodes.IADD) {
                replacement = polymorphIAdd(random);
            } else if (op == Opcodes.ISUB) {
                replacement = polymorphISub(random);
            } else if (op == Opcodes.IXOR) {
                replacement = polymorphIXor(random);
            } else if (op >= Opcodes.ICONST_0 && op <= Opcodes.ICONST_5) {
                replacement = polymorphIConst(op - Opcodes.ICONST_0, random);
            }

            if (replacement != null) {
                insns.insertBefore(node, replacement);
                insns.remove(node);
                count++;
            }
        }
        return count;
    }

    private InsnList polymorphIAdd(Random random) {
        InsnList list = new InsnList();
        if (random.nextBoolean()) {
            // a + b => a - (-b)
            list.add(new InsnNode(Opcodes.INEG));
            list.add(new InsnNode(Opcodes.ISUB));
        } else {
            // a + b => (a + delta) + (b - delta)
            int delta = random.nextInt(100) + 1;
            list.add(new LdcInsnNode(delta));
            list.add(new InsnNode(Opcodes.ISUB));
            list.add(new InsnNode(Opcodes.SWAP));
            list.add(new LdcInsnNode(delta));
            list.add(new InsnNode(Opcodes.IADD));
            list.add(new InsnNode(Opcodes.IADD));
        }
        return list;
    }

    private InsnList polymorphISub(Random random) {
        InsnList list = new InsnList();
        // a - b => a + (-b)
        list.add(new InsnNode(Opcodes.INEG));
        list.add(new InsnNode(Opcodes.IADD));
        return list;
    }

    private InsnList polymorphIXor(Random random) {
        InsnList list = new InsnList();
        // a ^ b => a + b - 2 * (a & b)
        // Stack: a, b
        list.add(new InsnNode(Opcodes.DUP2));   // a, b, a, b
        list.add(new InsnNode(Opcodes.IAND));   // a, b, (a & b)
        list.add(new InsnNode(Opcodes.ICONST_1)); // a, b, (a & b), 1
        list.add(new InsnNode(Opcodes.ISHL));   // a, b, 2*(a & b)
        list.add(new InsnNode(Opcodes.ISUB));   // a, (b - 2*(a & b))
        list.add(new InsnNode(Opcodes.IADD));   // a + b - 2*(a & b)
        return list;
    }

    private InsnList polymorphIConst(int val, Random random) {
        InsnList list = new InsnList();
        int delta = random.nextInt(10) + 1;
        // val => (val + delta) - delta
        list.add(new LdcInsnNode(val + delta));
        list.add(new LdcInsnNode(delta));
        list.add(new InsnNode(Opcodes.ISUB));
        return list;
    }
}
