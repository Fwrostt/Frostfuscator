package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.flow.ssa.SsaFlowExceptionPass;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.util.AccessHelper;
import java.security.SecureRandom;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

public class FlowExceptionTransformer extends Transformer {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override public String getName() { return "flow-exception"; }
    @Override public boolean runsPostRemap() { return true; }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String strength = config.getOption("strength", "GOOD").toUpperCase();
        int passes = strength.equals("AGGRESSIVE") ? 2 : 1;
        int probability = strength.equals("WEAK") ? 35 : 70;
        long configuredSeed = config.getOptionLong("seed", 0L);
        long runSeed = configuredSeed == 0L ? RANDOM.nextLong() : configuredSeed;
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) return;
            long changed = 0, skipped = 0;
            for (int index = 0; index < classNode.methods.size(); index++) {
                MethodNode method = classNode.methods.get(index);
                if (method.instructions == null || method.instructions.size() == 0
                        || AccessHelper.isInitializer(method)
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                long seed = runSeed ^ classNode.name.hashCode() ^ method.name.hashCode() ^ method.desc.hashCode();
                var result = adapter.run(classNode.name, method,
                        new SsaFlowExceptionPass(probability, passes, (int) seed), seed);
                if (result.changed()) {
                    classNode.methods.set(index, result.output().orElseThrow());
                    changed += result.metric("exceptionGuards");
                } else if (result.status() != IrMethodPassAdapter.Status.UNCHANGED) {
                    skipped++;
                }
            }
            if (changed > 0) {
                pool.markFramesDirty(classNode.name);
                detail("Inserted {} SSA exception-driven guards in {}", changed, classNode.name);
            }
            if (skipped > 0) detail("Safely skipped {} non-lowerable exception method(s) in {}", skipped, classNode.name);
        });
    }
}
