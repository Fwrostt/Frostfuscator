package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.util.Logger;
import dev.frost.ir.pass.MethodSaltingPass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

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
        LongAdder saltedMethods = new LongAdder();
        int maxSaltsPerMethod = config.getOptionInt("max-salts", 4);
        int probability = config.getOptionInt("probability", 75);
        long seed = config.getOptionLong("seed", 777L);

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                return;
            }
            if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) return;

            boolean classChanged = false;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                Random random = new Random(seed ^ classNode.name.hashCode() ^ method.name.hashCode());
                if (random.nextInt(100) >= probability) continue;

                var result = new IrMethodPassAdapter().run(classNode.name, method,
                        new MethodSaltingPass(maxSaltsPerMethod), random.nextLong());
                int saltsAdded = result.changed() ? Math.toIntExact(result.metric("salts")) : 0;
                if (saltsAdded > 0) {
                    IrMethodPassAdapter.publishBody(method, result.output().orElseThrow());
                    saltedMethods.increment();
                    classChanged = true;
                }
            }
            if (classChanged) pool.markDirty(classNode.name);
        });

        Logger.info("Salted {} method(s) with unique opcode sequences", saltedMethods.sum());
    }

}
