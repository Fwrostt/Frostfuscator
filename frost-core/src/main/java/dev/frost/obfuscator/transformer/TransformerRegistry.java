package dev.frost.obfuscator.transformer;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.transformer.cleanup.AccessModifierTransformer;
import dev.frost.obfuscator.transformer.cleanup.LineNumberMutationTransformer;
import dev.frost.obfuscator.transformer.cleanup.MetadataNoiseTransformer;
import dev.frost.obfuscator.transformer.cleanup.RemoveDebugTransformer;
import dev.frost.obfuscator.transformer.encryption.NumberObfuscationTransformer;
import dev.frost.obfuscator.transformer.encryption.ParameterEncryptionTransformer;
import dev.frost.obfuscator.transformer.encryption.StringEncryptionTransformer;
import dev.frost.obfuscator.transformer.encryption.StringSplittingTransformer;
import dev.frost.obfuscator.transformer.flow.ControlFlowShufflingTransformer;
import dev.frost.obfuscator.transformer.flow.FlowConditionTransformer;
import dev.frost.obfuscator.transformer.flow.FlowExceptionTransformer;
import dev.frost.obfuscator.transformer.flow.FlowObfuscationTransformer;
import dev.frost.obfuscator.transformer.flow.FlowOutlinerTransformer;
import dev.frost.obfuscator.transformer.flow.FlowRangeTransformer;
import dev.frost.obfuscator.transformer.flow.FlowSwitchTransformer;
import dev.frost.obfuscator.transformer.flow.MixedBooleanArithmeticTransformer;
import dev.frost.obfuscator.transformer.flow.PolymorphTransformer;
import dev.frost.obfuscator.transformer.flow.StackManipulationTransformer;
import dev.frost.obfuscator.transformer.funsies.BannerInjectionTransformer;
import dev.frost.obfuscator.transformer.funsies.ChineseModeTransformer;
import dev.frost.obfuscator.transformer.funsies.CopypastaInjectorTransformer;
import dev.frost.obfuscator.transformer.funsies.EmojiHellTransformer;
import dev.frost.obfuscator.transformer.funsies.DecompilerCrasherTransformer;
import dev.frost.obfuscator.transformer.funsies.DecompilerZipTiesTransformer;
import dev.frost.obfuscator.transformer.funsies.LanguageMixupTransformer;
import dev.frost.obfuscator.transformer.funsies.TrollStackTracesTransformer;
import dev.frost.obfuscator.transformer.indirection.CondyIndirectionTransformer;
import dev.frost.obfuscator.transformer.indirection.InvokeDynamicTransformer;
import dev.frost.obfuscator.transformer.indirection.ReferenceHidingTransformer;
import dev.frost.obfuscator.transformer.indirection.ReflectionHidingTransformer;
import dev.frost.obfuscator.transformer.license.LicenseGuardTransformer;
import dev.frost.obfuscator.transformer.optimization.BytecodeOptimizerTransformer;
import dev.frost.obfuscator.transformer.optimization.JarShrinkerTransformer;
import dev.frost.obfuscator.transformer.optimization.AggressiveInliningTransformer;
import dev.frost.obfuscator.transformer.optimization.DeadCodeEliminationTransformer;
import dev.frost.obfuscator.transformer.protection.AntiAgentTransformer;
import dev.frost.obfuscator.transformer.protection.AntiDebugTransformer;
import dev.frost.obfuscator.transformer.protection.AntiDecompilerTransformer;
import dev.frost.obfuscator.transformer.protection.AntiAttachTransformer;
import dev.frost.obfuscator.transformer.protection.ArchiveExtractionCanaryTransformer;
import dev.frost.obfuscator.transformer.protection.ClassSaltingTransformer;
import dev.frost.obfuscator.transformer.protection.EncryptedClassLoaderTransformer;
import dev.frost.obfuscator.transformer.protection.FakeApplicationTransformer;
import dev.frost.obfuscator.transformer.protection.FakeClassTransformer;
import dev.frost.obfuscator.transformer.protection.IntegrityTransformer;
import dev.frost.obfuscator.transformer.protection.JunkCodeTransformer;
import dev.frost.obfuscator.transformer.protection.MethodSaltingTransformer;
import dev.frost.obfuscator.transformer.protection.RuntimeSelfChecksumTransformer;
import dev.frost.obfuscator.transformer.protection.StructuralHardeningTransformer;
import dev.frost.obfuscator.transformer.reporting.StatisticsReportTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceCompressionTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceEncryptionTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceSplittingTransformer;
import dev.frost.obfuscator.transformer.resources.ResourceSteganographyTransformer;
import dev.frost.obfuscator.transformer.watermark.WatermarkTransformer;
import dev.frost.obfuscator.transformer.virtualization.VirtualizationTransformer;
import dev.frost.obfuscator.transformer.rename.ClassRenameTransformer;
import dev.frost.obfuscator.transformer.rename.FieldRenameTransformer;
import dev.frost.obfuscator.transformer.rename.LocalVariableRenameTransformer;
import dev.frost.obfuscator.transformer.rename.MethodRenameTransformer;
import dev.frost.obfuscator.transformer.rename.KotlinMetadataTransformer;
import dev.frost.obfuscator.plugin.PluginDescriptor;
import dev.frost.obfuscator.plugin.PluginLoader;
import dev.frost.obfuscator.plugin.LoadedPlugin;
import dev.frost.obfuscator.util.Logger;

import java.nio.file.Path;
import java.util.*;

public class TransformerRegistry {

    private static final Map<String, Transformer> TRANSFORMERS = new LinkedHashMap<>();
    private static final PluginLoader PLUGIN_LOADER = new PluginLoader();

    static {
        register(new LicenseGuardTransformer());
        register(new StringSplittingTransformer());
        register(new LanguageMixupTransformer());
        register(new ClassRenameTransformer());
        register(new FieldRenameTransformer());
        register(new MethodRenameTransformer());
        register(new KotlinMetadataTransformer());
        register(new LocalVariableRenameTransformer());
        register(new RemoveDebugTransformer());
        register(new StringEncryptionTransformer());
        register(new NumberObfuscationTransformer());
        register(new MixedBooleanArithmeticTransformer());
        register(new ParameterEncryptionTransformer());
        register(new FlowObfuscationTransformer());
        register(new FlowOutlinerTransformer());
        register(new FlowRangeTransformer());
        register(new FlowConditionTransformer());
        register(new FlowExceptionTransformer());
        register(new FlowSwitchTransformer());
        register(new ControlFlowShufflingTransformer());
        register(new LineNumberMutationTransformer());
        register(new MethodSaltingTransformer());
        register(new ClassSaltingTransformer());
        register(new PolymorphTransformer());
        register(new StackManipulationTransformer());
        register(new ReflectionHidingTransformer());
        register(new InvokeDynamicTransformer());
        register(new CondyIndirectionTransformer());
        register(new ReferenceHidingTransformer());
        register(new AccessModifierTransformer());
        register(new MetadataNoiseTransformer());
        register(new WatermarkTransformer());
        register(new IntegrityTransformer());
        register(new AntiDebugTransformer());
        register(new AntiAttachTransformer());
        register(new AntiAgentTransformer());
        register(new RuntimeSelfChecksumTransformer());
        register(new AntiDecompilerTransformer());
        register(new StructuralHardeningTransformer());
        register(new ArchiveExtractionCanaryTransformer());
        register(new JunkCodeTransformer());
        register(new FakeApplicationTransformer());
        register(new FakeClassTransformer());
        register(new EncryptedClassLoaderTransformer());
        register(new VirtualizationTransformer());
        register(new BannerInjectionTransformer());
        register(new EmojiHellTransformer());
        register(new CopypastaInjectorTransformer());
        register(new ChineseModeTransformer());
        register(new DecompilerZipTiesTransformer());
        register(new DecompilerCrasherTransformer());
        register(new TrollStackTracesTransformer());
        register(new ResourceCompressionTransformer());
        register(new ResourceEncryptionTransformer());
        register(new ResourceSplittingTransformer());
        register(new ResourceSteganographyTransformer());
        register(new AggressiveInliningTransformer());
        register(new DeadCodeEliminationTransformer());
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

    public static synchronized void registerExternal(Transformer transformer) {
        Objects.requireNonNull(transformer, "transformer");
        if (TRANSFORMERS.containsKey(transformer.getName())) {
            throw new IllegalArgumentException("Transformer id is already registered: " + transformer.getName());
        }
        TRANSFORMERS.put(transformer.getName(), transformer);
        Logger.info("Registered plugin transformer: {}", transformer.getName());
    }

    public static synchronized void unregisterExternal(Transformer transformer) {
        if (transformer != null && TRANSFORMERS.remove(transformer.getName(), transformer)) {
            Logger.info("Unregistered plugin transformer: {}", transformer.getName());
        }
    }

    private static void discoverPlugins() {
        ServiceLoader<Transformer> loader = ServiceLoader.load(Transformer.class);
        for (Transformer transformer : loader) {
            register(transformer);
            Logger.info("Loaded plugin transformer: {}", transformer.getName());
        }
    }

    public static List<PluginDescriptor> discoverPlugins(List<Path> directories) {
        return PLUGIN_LOADER.loadDirectories(directories, TransformerRegistry::registerExternal,
                TransformerRegistry::unregisterExternal).stream().map(LoadedPlugin::descriptor).toList();
    }

    public static Optional<LoadedPlugin> loadPlugin(Path jarPath) {
        return PLUGIN_LOADER.loadPlugin(jarPath, TransformerRegistry::registerExternal,
                TransformerRegistry::unregisterExternal);
    }

    public static Optional<LoadedPlugin> reloadPlugin(Path jarPath) {
        return PLUGIN_LOADER.reloadPlugin(jarPath, TransformerRegistry::registerExternal,
                TransformerRegistry::unregisterExternal);
    }

    public static boolean unloadPlugin(Path jarPath) {
        return PLUGIN_LOADER.unloadPlugin(jarPath);
    }

    public static List<LoadedPlugin> loadedPlugins() {
        return PLUGIN_LOADER.loadedPlugins();
    }

    public static List<Transformer> getEnabled(ObfuscationConfig config) {
        return getEnabled(config, null);
    }

    public static synchronized List<Transformer> getEnabled(ObfuscationConfig config, List<String> cliOverride) {
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

    public static synchronized Transformer getByName(String name) {
        return TRANSFORMERS.get(name);
    }

    public static synchronized Collection<String> getAllNames() {
        return Set.copyOf(TRANSFORMERS.keySet());
    }
}
