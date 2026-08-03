package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.flow.ssa.SsaFlowConditionPass;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.util.AccessHelper;
import java.security.SecureRandom;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

public class FlowConditionTransformer extends Transformer {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override public String getName() { return "flow-condition"; }
    @Override public boolean runsPostRemap() { return true; }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(config.getOptionInt("probability", 25), 0, 100);
        int maxPerMethod = Math.max(0, config.getOptionInt("max-per-method", 16));
        long runSeed = config.getOptionLong("seed", 0L);
        if (runSeed == 0L) runSeed = RANDOM.nextLong();
        long seedBase = runSeed;
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) return;
            long changed = 0, skipped = 0;
            for (int index = 0; index < classNode.methods.size(); index++) {
                MethodNode method = classNode.methods.get(index);
                if (method.instructions == null || method.instructions.size() == 0
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                long seed = seedBase ^ classNode.name.hashCode() ^ method.name.hashCode() ^ method.desc.hashCode();
                var result = adapter.run(classNode.name, method,
                        new SsaFlowConditionPass(probability, maxPerMethod, (int) seed), seed);
                if (result.changed()) {
                    classNode.methods.set(index, result.output().orElseThrow());
                    changed += result.metric("guards");
                } else if (result.status() != IrMethodPassAdapter.Status.UNCHANGED) {
                    skipped++;
                }
            }
            if (changed > 0) {
                pool.markFramesDirty(classNode.name);
                detail("Inserted {} SSA conditional guards in {}", changed, classNode.name);
            }
            if (skipped > 0) detail("Safely skipped {} non-lowerable conditional method(s) in {}", skipped, classNode.name);
        });
    }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
