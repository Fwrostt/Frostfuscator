package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

public class ClassSaltingTransformer extends Transformer {

    @Override
    public String getName() {
        return "class-salting";
    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        LongAdder saltedClasses = new LongAdder();
        int fieldsPerClass = config.getOptionInt("fields-per-class", 2);
        long seed = config.getOptionLong("seed", 9999L);

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                return;
            }
            if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) return;

            Random random = new Random(seed ^ classNode.name.hashCode());
            for (int i = 0; i < fieldsPerClass; i++) {
                String fieldName = "$salt_" + Integer.toHexString(random.nextInt(0xFFFFFF));
                int val = random.nextInt();
                FieldNode saltField = new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                        fieldName,
                        "I",
                        null,
                        val
                );
                classNode.fields.add(saltField);
            }
            pool.markDirty(classNode.name);
            saltedClasses.increment();
        });

        Logger.info("Salted {} class(es) with synthetic structural fields", saltedClasses.sum());
    }
}
