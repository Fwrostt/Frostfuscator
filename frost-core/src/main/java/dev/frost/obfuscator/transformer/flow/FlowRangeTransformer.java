package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.util.AccessHelper;
import dev.frost.ir.pass.FlowRangePass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;

public class FlowRangeTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "flow-range";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 35), 0, 100);
        boolean includeSynthetic = getBooleanOption(config, "include-synthetic", false);

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                return;
            }

            int changed = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if (AccessHelper.isInitializer(method)) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                if (!includeSynthetic && (method.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
                if (RANDOM.nextInt(100) < probability) {
                    var result = new IrMethodPassAdapter().run(classNode.name, method,
                            new FlowRangePass(), RANDOM.nextLong());
                    if (result.changed()) {
                        IrMethodPassAdapter.publishBody(method, result.output().orElseThrow());
                        changed++;
                    }
                }
            }

            if (changed > 0) {
                pool.markFramesDirty(classNode.name);
                detail("Wrapped {} methods in synthetic exception ranges in {}", changed, classNode.name);
            }
        });
    }

    private int getIntOption(TransformerConfig config, String key, int defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean getBooleanOption(TransformerConfig config, String key, boolean defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Boolean bool) return bool;
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
