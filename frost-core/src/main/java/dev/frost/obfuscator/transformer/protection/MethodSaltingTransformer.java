package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Random;

public class MethodSaltingTransformer extends Transformer {

    @Override
    public String getName() {
        return "method-salting";
    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int saltedMethods = 0;
        int maxSaltsPerMethod = config.getOptionInt("max-salts", 4);
        int probability = config.getOptionInt("probability", 75);
        long seed = config.getOptionLong("seed", 777L);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }
            if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) continue;

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                Random random = new Random(seed ^ classNode.name.hashCode() ^ method.name.hashCode());
                if (random.nextInt(100) >= probability) continue;

                int saltsAdded = injectMethodSalts(method, maxSaltsPerMethod, random);
                if (saltsAdded > 0) {
                    saltedMethods++;
                }
            }
        }

        Logger.info("Salted {} method(s) with unique opcode sequences", saltedMethods);
    }

    private int injectMethodSalts(MethodNode method, int maxSalts, Random random) {
        InsnList insns = method.instructions;
        AbstractInsnNode[] nodes = insns.toArray();
        int count = 0;

        for (AbstractInsnNode node : nodes) {
            if (count >= maxSalts) break;
            if (node instanceof LineNumberNode || node instanceof LabelNode || node instanceof FrameNode) continue;

            InsnList saltSequence = generateSalt(random);
            insns.insert(node, saltSequence);
            count++;
        }
        return count;
    }

    private InsnList generateSalt(Random random) {
        InsnList salt = new InsnList();
        int type = random.nextInt(4);

        switch (type) {
            case 0 -> {
                // Double NOP sequence
                salt.add(new InsnNode(Opcodes.NOP));
                salt.add(new InsnNode(Opcodes.NOP));
            }
            case 1 -> {
                // Identity integer addition: + 0
                salt.add(new InsnNode(Opcodes.ICONST_0));
                salt.add(new InsnNode(Opcodes.IADD));
            }
            case 2 -> {
                // Identity integer multiplication: * 1
                salt.add(new InsnNode(Opcodes.ICONST_1));
                salt.add(new InsnNode(Opcodes.IMUL));
            }
            case 3 -> {
                // Identity integer subtraction: - 0
                salt.add(new InsnNode(Opcodes.ICONST_0));
                salt.add(new InsnNode(Opcodes.ISUB));
            }
        }
        return salt;
    }
}
