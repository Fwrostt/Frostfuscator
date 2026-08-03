package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.flow.ssa.SsaControlFlowShufflingPass;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.util.Logger;
import java.util.concurrent.atomic.LongAdder;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

public class ControlFlowShufflingTransformer extends Transformer {
    @Override public String getName() { return "control-flow-shuffling"; }
    @Override public Priority priority() { return Priority.NORMAL; }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        LongAdder shuffledMethods = new LongAdder();
        LongAdder skippedMethods = new LongAdder();
        int probability = Math.max(0, Math.min(100, config.getOptionInt("probability", 50)));
        long globalSeed = config.getOptionLong("seed", 1337L);
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || (classNode.access & Opcodes.ACC_INTERFACE) != 0) return;
            boolean changed = false;
            for (int index = 0; index < classNode.methods.size(); index++) {
                MethodNode method = classNode.methods.get(index);
                if (method.instructions == null || method.instructions.size() < 6
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                long seed = globalSeed ^ classNode.name.hashCode() ^ method.name.hashCode() ^ method.desc.hashCode();
                if (new java.util.SplittableRandom(seed).nextInt(100) >= probability) continue;
                var result = adapter.run(classNode.name, method, new SsaControlFlowShufflingPass(), seed);
                if (result.changed()) {
                    classNode.methods.set(index, result.output().orElseThrow());
                    shuffledMethods.increment();
                    changed = true;
                } else if (result.status() != IrMethodPassAdapter.Status.UNCHANGED) {
                    skippedMethods.increment();
                }
            }
            if (changed) pool.markFramesDirty(classNode.name);
        });
        Logger.info("Shuffled control flow basic blocks in {} method(s); safely skipped {} method(s)",
                shuffledMethods.sum(), skippedMethods.sum());
    }
}
