package dev.frost.obfuscator.transformer;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.transformer.cleanup.AccessModifierTransformer;
import dev.frost.obfuscator.transformer.cleanup.MetadataNoiseTransformer;
import dev.frost.obfuscator.transformer.cleanup.RemoveDebugTransformer;
import dev.frost.obfuscator.transformer.encryption.NumberObfuscationTransformer;
import dev.frost.obfuscator.transformer.encryption.ParameterEncryptionTransformer;
import dev.frost.obfuscator.transformer.encryption.StringEncryptionTransformer;
import dev.frost.obfuscator.transformer.flow.FlowConditionTransformer;
import dev.frost.obfuscator.transformer.flow.FlowExceptionTransformer;
import dev.frost.obfuscator.transformer.flow.FlowObfuscationTransformer;
import dev.frost.obfuscator.transformer.flow.FlowOutlinerTransformer;
import dev.frost.obfuscator.transformer.flow.FlowRangeTransformer;
import dev.frost.obfuscator.transformer.flow.FlowSwitchTransformer;
import dev.frost.obfuscator.transformer.flow.StackManipulationTransformer;
import dev.frost.obfuscator.transformer.indirection.InvokeDynamicTransformer;
import dev.frost.obfuscator.transformer.indirection.ReferenceHidingTransformer;
import dev.frost.obfuscator.transformer.optimization.BytecodeOptimizerTransformer;
import dev.frost.obfuscator.transformer.optimization.JarShrinkerTransformer;
import dev.frost.obfuscator.transformer.protection.AntiDebugTransformer;
import dev.frost.obfuscator.transformer.protection.AntiDecompilerTransformer;
import dev.frost.obfuscator.transformer.protection.IntegrityTransformer;
import dev.frost.obfuscator.transformer.reporting.StatisticsReportTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceCompressionTransformer;
import dev.frost.obfuscator.transformer.watermark.WatermarkTransformer;
import dev.frost.obfuscator.transformer.rename.ClassRenameTransformer;
import dev.frost.obfuscator.transformer.rename.FieldRenameTransformer;
import dev.frost.obfuscator.transformer.rename.LocalVariableRenameTransformer;
import dev.frost.obfuscator.transformer.rename.MethodRenameTransformer;
import dev.frost.obfuscator.util.Logger;

import java.util.*;

public class TransformerRegistry {

    private static final Map<String, Transformer> TRANSFORMERS = new LinkedHashMap<>();

    static {
        register(new ClassRenameTransformer());
        register(new FieldRenameTransformer());
        register(new MethodRenameTransformer());
        register(new LocalVariableRenameTransformer());
        register(new RemoveDebugTransformer());
        register(new StringEncryptionTransformer());
        register(new NumberObfuscationTransformer());
        register(new ParameterEncryptionTransformer());
        register(new FlowObfuscationTransformer());
        register(new FlowOutlinerTransformer());
        register(new FlowRangeTransformer());
        register(new FlowConditionTransformer());
        register(new FlowExceptionTransformer());
        register(new FlowSwitchTransformer());
        register(new StackManipulationTransformer());
        register(new InvokeDynamicTransformer());
        register(new ReferenceHidingTransformer());
        register(new AccessModifierTransformer());
        register(new MetadataNoiseTransformer());
        register(new WatermarkTransformer());
        register(new IntegrityTransformer());
        register(new AntiDebugTransformer());
        register(new AntiDecompilerTransformer());
        register(new ResourceCompressionTransformer());
        register(new BytecodeOptimizerTransformer());
        register(new JarShrinkerTransformer());
        register(new StatisticsReportTransformer());
        discoverPlugins();
    }

    private static void register(Transformer transformer) {
        Transformer previous = TRANSFORMERS.put(transformer.getName(), transformer);
        if (previous != null) {
            Logger.warn("Transformer '{}' was replaced by {}", transformer.getName(), transformer.getClass().getName());
        }
    }

    private static void discoverPlugins() {
        ServiceLoader<Transformer> loader = ServiceLoader.load(Transformer.class);
        for (Transformer transformer : loader) {
            register(transformer);
            Logger.info("Loaded plugin transformer: {}", transformer.getName());
        }
    }

    public static List<Transformer> getEnabled(ObfuscationConfig config) {
        return getEnabled(config, null);
    }

    public static List<Transformer> getEnabled(ObfuscationConfig config, List<String> cliOverride) {
        List<Transformer> result = new ArrayList<>();

        if (cliOverride == null || cliOverride.isEmpty()) {
            for (String configured : config.getTransformers().keySet()) {
                if (!TRANSFORMERS.containsKey(configured)
                        && config.getTransformerConfig(configured) != null
                        && config.getTransformerConfig(configured).isEnabled()) {
                    Logger.warn("Configured transformer '{}' is not registered and will be ignored", configured);
                }
            }
        }

        for (Map.Entry<String, Transformer> entry : TRANSFORMERS.entrySet()) {
            String name = entry.getKey();
            Transformer transformer = entry.getValue();

            if (cliOverride != null && !cliOverride.isEmpty()) {
                if (cliOverride.contains(name)) {
                    result.add(transformer);
                }
                continue;
            }

            TransformerConfig tc = config.getTransformerConfig(name);
            if (tc != null && tc.isEnabled()) {
                result.add(transformer);
            }
        }

        return result;
    }

    public static Transformer getByName(String name) {
        return TRANSFORMERS.get(name);
    }

    public static Collection<String> getAllNames() {
        return Collections.unmodifiableSet(TRANSFORMERS.keySet());
    }
}
