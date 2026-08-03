package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.flow.ssa.SsaFlowSwitchPass;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import java.security.SecureRandom;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

public class FlowSwitchTransformer extends Transformer {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override public String getName() { return "flow-switch"; }
    @Override public boolean runsPostRemap() { return true; }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(config.getOptionInt("probability", 75), 0, 100);
        long configuredSeed = config.getOptionLong("seed", 0L);
        long runSeed = configuredSeed == 0L ? RANDOM.nextLong() : configuredSeed;
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) return;
            long changed = 0, skipped = 0;
            for (int index = 0; index < classNode.methods.size(); index++) {
                MethodNode method = classNode.methods.get(index);
                if (method.instructions == null || method.instructions.size() == 0
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                long seed = runSeed ^ classNode.name.hashCode() ^ method.name.hashCode() ^ method.desc.hashCode();
                var result = adapter.run(classNode.name, method, new SsaFlowSwitchPass(probability), seed);
                if (result.changed()) {
                    classNode.methods.set(index, result.output().orElseThrow());
                    changed += result.metric("convertedConditions") + result.metric("hashedSwitches");
                } else if (result.status() != IrMethodPassAdapter.Status.UNCHANGED) {
                    skipped++;
                }
            }
            if (changed > 0) {
                pool.markFramesDirty(classNode.name);
                detail("Rewrote {} SSA switch dispatches in {}", changed, classNode.name);
            }
            if (skipped > 0) detail("Safely skipped {} non-lowerable switch method(s) in {}", skipped, classNode.name);
        });
    }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
