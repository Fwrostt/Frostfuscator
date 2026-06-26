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
import dev.frost.obfuscator.transformer.funsies.BannerInjectionTransformer;
import dev.frost.obfuscator.transformer.funsies.ChineseModeTransformer;
import dev.frost.obfuscator.transformer.funsies.CopypastaInjectorTransformer;
import dev.frost.obfuscator.transformer.funsies.EmojiHellTransformer;
import dev.frost.obfuscator.transformer.indirection.InvokeDynamicTransformer;
import dev.frost.obfuscator.transformer.indirection.ReferenceHidingTransformer;
import dev.frost.obfuscator.transformer.license.LicenseGuardTransformer;
import dev.frost.obfuscator.transformer.optimization.BytecodeOptimizerTransformer;
import dev.frost.obfuscator.transformer.optimization.JarShrinkerTransformer;
import dev.frost.obfuscator.transformer.protection.AntiDebugTransformer;
import dev.frost.obfuscator.transformer.protection.AntiDecompilerTransformer;
import dev.frost.obfuscator.transformer.protection.EncryptedClassLoaderTransformer;
import dev.frost.obfuscator.transformer.protection.FakeApplicationTransformer;
import dev.frost.obfuscator.transformer.protection.FakeClassTransformer;
import dev.frost.obfuscator.transformer.protection.IntegrityTransformer;
import dev.frost.obfuscator.transformer.protection.JunkCodeTransformer;
import dev.frost.obfuscator.transformer.reporting.StatisticsReportTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceCompressionTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceEncryptionTransformer;
import dev.frost.obfuscator.transformer.watermark.WatermarkTransformer;
import dev.frost.obfuscator.transformer.virtualization.VirtualizationTransformer;
import dev.frost.obfuscator.transformer.rename.ClassRenameTransformer;
import dev.frost.obfuscator.transformer.rename.FieldRenameTransformer;
import dev.frost.obfuscator.transformer.rename.LocalVariableRenameTransformer;
import dev.frost.obfuscator.transformer.rename.MethodRenameTransformer;
import dev.frost.obfuscator.plugin.PluginDescriptor;
import dev.frost.obfuscator.plugin.PluginLoader;
import dev.frost.obfuscator.util.Logger;

import java.nio.file.Path;
import java.util.*;

public class TransformerRegistry {

    private static final Map<String, Transformer> TRANSFORMERS = new LinkedHashMap<>();
    private static final Set<Path> DISCOVERED_PLUGIN_DIRECTORIES = new LinkedHashSet<>();

    static {
        register(new LicenseGuardTransformer());
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
        register(new JunkCodeTransformer());
        register(new FakeApplicationTransformer());
        register(new FakeClassTransformer());
        register(new EncryptedClassLoaderTransformer());
        register(new VirtualizationTransformer());
        register(new BannerInjectionTransformer());
        register(new EmojiHellTransformer());
        register(new CopypastaInjectorTransformer());
        register(new ChineseModeTransformer());
        register(new ResourceCompressionTransformer());
        register(new ResourceEncryptionTransformer());
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

    public static void registerExternal(Transformer transformer) {
        register(transformer);
        Logger.info("Registered plugin transformer: {}", transformer.getName());
    }

    private static void discoverPlugins() {
        ServiceLoader<Transformer> loader = ServiceLoader.load(Transformer.class);
        for (Transformer transformer : loader) {
            register(transformer);
            Logger.info("Loaded plugin transformer: {}", transformer.getName());
        }
    }

    public static List<PluginDescriptor> discoverPlugins(List<Path> directories) {
        List<Path> newDirectories = new ArrayList<>();
        for (Path directory : directories) {
            if (directory == null) {
                continue;
            }
            Path normalized = directory.toAbsolutePath().normalize();
            if (DISCOVERED_PLUGIN_DIRECTORIES.add(normalized)) {
                newDirectories.add(normalized);
            }
        }
        if (newDirectories.isEmpty()) {
            return List.of();
        }
        return new PluginLoader().loadDirectories(newDirectories, TransformerRegistry::registerExternal);
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
