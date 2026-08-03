package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.util.Logger;
import dev.frost.ir.pass.PolymorphPass;
import java.util.concurrent.atomic.LongAdder;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

/** Applies verifier-safe instruction substitutions over Frost-IR SSA values. */
public final class PolymorphTransformer extends Transformer {
    private static final int MAX_OUTPUT_INSTRUCTIONS = 20_000;

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
        LongAdder skippedMethods = new LongAdder();
        int probability = Math.max(0, Math.min(100, config.getOptionInt("probability", 60)));
        int maximumPerMethod = Math.max(0, config.getOptionInt("max-per-method", 512));
        int maximumOutputInstructions = Math.max(64, Math.min(MAX_OUTPUT_INSTRUCTIONS,
                config.getOptionInt("max-output-method-instructions", 12_000)));
        boolean spreadAcrossBlocks = config.getOptionBoolean("spread-across-blocks", true);
        long seed = config.getOptionLong("seed", 12345L);
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                return;
            }
            boolean classChanged = false;
            for (int methodIndex = 0; methodIndex < classNode.methods.size(); methodIndex++) {
                MethodNode method = classNode.methods.get(methodIndex);
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                PolymorphPass pass = new PolymorphPass(new PolymorphPass.Options(
                        probability, maximumPerMethod, spreadAcrossBlocks));
                var result = adapter.run(classNode.name, method, pass,
                        methodSeed(seed, classNode.name, method));
                if (!result.changed()) {
                    if (result.status() == IrMethodPassAdapter.Status.UNSUPPORTED
                            || result.status() == IrMethodPassAdapter.Status.PASS_FAILED
                            || result.status() == IrMethodPassAdapter.Status.LOWERING_FAILED) {
                        skippedMethods.increment();
                    }
                    continue;
                }
                MethodNode output = result.output().orElseThrow();
                if (output.instructions.size() > maximumOutputInstructions) {
                    skippedMethods.increment();
                    continue;
                }
                publishBody(method, output);
                polymorphedInstructions.add(result.metric("substituted"));
                classChanged = true;
            }
            if (classChanged) pool.markFramesDirty(classNode.name);
        });

        Logger.info("Applied polymorphic SSA substitution to {} operation(s); skipped {} method(s)",
                polymorphedInstructions.sum(), skippedMethods.sum());
    }

    private long methodSeed(long seed, String owner, MethodNode method) {
        return mix(seed ^ stableHash(owner + '.' + method.name + method.desc));
    }

    /** Preserve the legacy transformer's in-place MethodNode identity for plugin callers. */
    private void publishBody(MethodNode target, MethodNode output) {
        target.instructions = output.instructions;
        target.tryCatchBlocks = output.tryCatchBlocks;
        target.localVariables = output.localVariables;
        target.visibleLocalVariableAnnotations = output.visibleLocalVariableAnnotations;
        target.invisibleLocalVariableAnnotations = output.invisibleLocalVariableAnnotations;
        target.maxStack = output.maxStack;
        target.maxLocals = output.maxLocals;
    }

    private long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
