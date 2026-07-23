package dev.frost.obfuscator.transformer.cleanup;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Random;

public class LineNumberMutationTransformer extends Transformer {

    @Override
    public String getName() {
        return "line-number-mutation";
    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int mutatedLines = 0;
        int minLine = config.getOptionInt("min-line", 1000);
        int maxLine = config.getOptionInt("max-line", 9999);
        long seed = config.getOptionLong("seed", 42L);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;
                Random random = new Random(seed ^ classNode.name.hashCode() ^ method.name.hashCode());

                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof LineNumberNode lineNode) {
                        int fakeLine = minLine + random.nextInt(Math.max(1, maxLine - minLine + 1));
                        lineNode.line = fakeLine;
                        mutatedLines++;
                    }
                }
            }
        }

        Logger.info("Mutated {} LineNumberNode debug entry/entries to randomized values", mutatedLines);
    }
}
