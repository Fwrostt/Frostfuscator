package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.jni.FrostJNIProtectionService;
import dev.frost.obfuscator.jni.FrostJNIResult;
import dev.frost.obfuscator.jni.NativeProtectionRequest;
import dev.frost.obfuscator.remapper.FrostRemapper;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.TransformerRegistry;
import dev.frost.obfuscator.transformer.reporting.StatisticsReportTransformer;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class ObfuscationEngine {

    private final ObfuscationConfig config;
    private final List<String> cliTransformers;

    public ObfuscationEngine(ObfuscationConfig config, List<String> cliTransformers) {
        this.config = config;
        this.cliTransformers = cliTransformers;
    }

    public void run() throws IOException {
        long startTime = System.currentTimeMillis();
        Path inputPath = Path.of(config.getInput());
        Path outputPath = Path.of(config.getOutput());
        ProtectionStats stats = new ProtectionStats();

        Logger.info("===================================================");
        Logger.info("  Frostfuscator - Java obfuscation toolkit");
        Logger.info("===================================================");
        Logger.info("Input:  {}", config.getInput());
        Logger.info("Output: {}", config.getOutput());
        Logger.info("Dictionary: {}", config.getDictionary());
        Logger.info("Package mode: {}", config.getPackageMode());
        if (config.getLibs() != null && !config.getLibs().isEmpty()) {
            Logger.info("Libraries: {}", config.getLibs());
        }
        if (config.getInclusions() != null && !config.getInclusions().isEmpty()) {
            Logger.info("Inclusions: {}", config.getInclusions());
        }
        Logger.info("");

        JarProcessor processor = new JarProcessor();
        ClassPool pool = processor.loadJar(inputPath);
        stats.set("classes", pool.size());

        if (config.getLibs() != null && !config.getLibs().isEmpty()) {
            processor.loadLibraries(pool, Path.of(config.getLibs()));
        }

        List<String> exclusions = new ArrayList<>(config.getExclusions() != null ? config.getExclusions() : List.of());

        String detectedMainClass = processor.getDetectedMainClass();
        String mainClassInternal = null;
        String manifestMainClass = processor.getManifestMainClass();
        String manifestMainClassInternal = null;
        if (detectedMainClass != null) {
            mainClassInternal = detectedMainClass.replace('.', '/');
            Logger.info("Detected plugin main class: {} (will be renamed, plugin.yml will be updated)", detectedMainClass);
        }
        if (manifestMainClass != null) {
            manifestMainClassInternal = manifestMainClass.replace('.', '/');
            Logger.info("Detected manifest main class: {} (manifest will be updated if renamed)", manifestMainClass);
        }

        pool.setGlobalExclusions(exclusions);
        pool.setGlobalInclusions(config.getInclusions());
        pool.setPackageMode(config.getPackageMode());
        pool.setFlattenPackage(config.getFlattenPackage());

        MappingCollector mappings = new MappingCollector();

        List<Transformer> allTransformers = TransformerRegistry.getEnabled(config, cliTransformers);
        List<Transformer> preObfuscation = new ArrayList<>();
        List<Transformer> normal = new ArrayList<>();
        List<Transformer> postRemap = new ArrayList<>();
        List<Transformer> finalPass = new ArrayList<>();
        for (Transformer t : allTransformers) {
            switch (t.priority()) {
                case PRE_OBFUSCATION -> preObfuscation.add(t);
                case POST_REMAP -> postRemap.add(t);
                case FINAL -> finalPass.add(t);
                default -> normal.add(t);
            }
        }

        if (!preObfuscation.isEmpty()) {
            Logger.info("Pass 0: Pre-obfuscation generation");
            for (Transformer transformer : preObfuscation) {
                TransformerConfig tc = resolveConfig(transformer);
                Logger.info("Running transformer: {}", transformer.getName());
                transformer.transform(new Context(pool, processor, mappings, tc, stats, inputPath, outputPath));
            }
            Logger.info("");
        }

        pool.buildHierarchy();
        Logger.info("Class hierarchy built ({} app + {} library classes)",
                pool.size(), pool.librarySize());

        Logger.info("Active transformers: {}", allTransformers.stream().map(Transformer::getName).toList());
        Logger.info("");

        Logger.info("Pass 1: Collecting mappings");
        for (Transformer transformer : normal) {
            TransformerConfig tc = resolveConfig(transformer);
            Logger.info("Running transformer: {}", transformer.getName());
            transformer.transform(new Context(pool, processor, mappings, tc, stats, inputPath, outputPath));
        }

        Logger.info("");
        Logger.info("Pass 2: Applying remapping");
        Logger.info("Total mappings collected: {}", mappings.totalMappings());

        applyRemapping(pool, mappings);
        processor.snapshotPreFlowClasses(pool);

        if (detectedMainClass != null && mainClassInternal != null) {
            String newMainInternal = mappings.getMappedClass(mainClassInternal);
            if (!newMainInternal.equals(mainClassInternal)) {
                String newMainClass = newMainInternal.replace('/', '.');
                processor.updatePluginMainClass(detectedMainClass, newMainClass);
            }
        }
        if (manifestMainClass != null && manifestMainClassInternal != null) {
            String newMainInternal = mappings.getMappedClass(manifestMainClassInternal);
            if (!newMainInternal.equals(manifestMainClassInternal)) {
                processor.updateManifestMainClass(manifestMainClass, newMainInternal.replace('/', '.'));
            }
        }

        if (!postRemap.isEmpty()) {
            Logger.info("");
            Logger.info("Pass 3: Post-remap transforms");

            for (Transformer transformer : postRemap) {
                TransformerConfig tc = resolveConfig(transformer);
                Logger.info("Running transformer: {}", transformer.getName());
                transformer.transform(new Context(pool, processor, mappings, tc, stats, inputPath, outputPath));
            }
        }

        if (!finalPass.isEmpty()) {
            Logger.info("");
            Logger.info("Pass 4: Final transforms");

            for (Transformer transformer : finalPass) {
                TransformerConfig tc = resolveConfig(transformer);
                Logger.info("Running transformer: {}", transformer.getName());
                transformer.transform(new Context(pool, processor, mappings, tc, stats, inputPath, outputPath));
            }
        }

        if (config.getFrostJNI() != null && config.getFrostJNI().isEnabled()) {
            Logger.info("");
            Logger.info("Pass 5: FrostJNI native protection");
            try {
                FrostJNIResult nativeResult = new FrostJNIProtectionService().protect(
                        new NativeProtectionRequest(config.getFrostJNI(), pool, processor, inputPath, outputPath)
                );
                stats.set("nativeClassesConverted", nativeResult.classesConverted());
                stats.set("nativeMethodsConverted", nativeResult.methodsConverted());
                stats.set("nativeSourceBytes", nativeResult.nativeSourceBytes());
                stats.set("nativeCompilationTimeMs", nativeResult.compilationTimeMs());
                stats.set("nativeLibraries", nativeResult.generatedLibraries().size());
                stats.set("nativeExcludedClasses", nativeResult.excludedClasses().size());
                stats.set("nativeConversionFailures", nativeResult.conversionFailures().size());
                nativeResult.conversionFailures().forEach(message -> Logger.warn("[FrostJNI] {}", message));
            } catch (Exception exception) {
                stats.add("nativeConversionFailures", 1);
                if (config.getFrostJNI().isContinueOnFailure() || !config.getFrostJNI().isFailFast()) {
                    Logger.warn("[FrostJNI] Native protection failed; continuing with Java-only output: {}",
                            exception.getMessage());
                    Logger.warn("[FrostJNI] Output jar is NOT native-protected. Disable continueOnFailure/failFast=false to stop builds on native errors.");
                } else {
                    if (exception instanceof IOException ioException) {
                        throw ioException;
                    }
                    if (exception instanceof InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IOException("FrostJNI native protection interrupted", interruptedException);
                    }
                    throw new IOException("FrostJNI native protection failed", exception);
                }
            }
            rewriteStatisticsReportIfEnabled(pool, processor, mappings, stats, inputPath, outputPath);
        }

        if (config.getMapping() != null && config.getMapping().isEnabled()) {
            mappings.exportMappings(Path.of(config.getMapping().getOutput()));
        }

        stats.set("classMappings", mappings.getClassMappings().size());
        stats.set("fieldMappings", mappings.getFieldMappings().size());
        stats.set("methodMappings", mappings.getMethodMappings().size());
        stats.set("totalMappings", mappings.totalMappings());

        processor.writeJar(pool, outputPath);

        long elapsed = System.currentTimeMillis() - startTime;
        Logger.info("");
        Logger.info("===================================================");
        Logger.info("  Protection run completed in {}ms", elapsed);
        Logger.info("  Classes: {} | Mappings: {}", pool.size(), mappings.totalMappings());
        Logger.info("===================================================");
    }

    private TransformerConfig resolveConfig(Transformer transformer) {
        TransformerConfig tc = config.getTransformerConfig(transformer.getName());
        if (tc == null) {
            tc = new TransformerConfig();
            tc.setDictionary(config.getDictionary());
        }
        if (tc.getDictionary() == null) {
            tc.setDictionary(config.getDictionary());
        }
        return tc;
    }

    private void applyRemapping(ClassPool pool, MappingCollector mappings) {
        FrostRemapper remapper = new FrostRemapper(mappings);

        Map<String, ClassNode> remappedClasses = new LinkedHashMap<>();

        for (Map.Entry<String, ClassNode> entry : pool.getClassMap().entrySet()) {
            ClassNode original = entry.getValue();
            ClassNode remapped = new ClassNode();

            ClassRemapper classRemapper = new ClassRemapper(remapped, remapper);
            original.accept(classRemapper);

            remappedClasses.put(remapped.name, remapped);
            pool.setOriginalName(remapped.name, entry.getKey());
            if (!remapped.name.equals(entry.getKey()) || mappings.hasAnyMappingForClass(entry.getKey())) {
                pool.markDirty(remapped.name);
            }
        }

        pool.getClassMap().clear();
        pool.getClassMap().putAll(remappedClasses);

        Logger.info("Remapping applied to all {} classes", remappedClasses.size());
    }

    private void rewriteStatisticsReportIfEnabled(
            ClassPool pool,
            JarProcessor processor,
            MappingCollector mappings,
            ProtectionStats stats,
            Path inputPath,
            Path outputPath
    ) {
        TransformerConfig reportConfig = config.getTransformerConfig("statistics-report");
        boolean cliAllowsReport = cliTransformers == null || cliTransformers.contains("statistics-report");
        if (reportConfig == null || !reportConfig.isEnabled() || !cliAllowsReport) {
            return;
        }
        try {
            new StatisticsReportTransformer().transform(
                    new Context(pool, processor, mappings, reportConfig, stats, inputPath, outputPath)
            );
        } catch (Exception exception) {
            Logger.warn("[FrostJNI] Failed to refresh statistics report with native metrics: {}",
                    exception.getMessage());
        }
    }
}
