package dev.frost.obfuscator.engine;

import dev.frost.api.event.events.ClassTransformEvent;
import dev.frost.api.event.events.PostObfuscationEvent;
import dev.frost.api.event.events.PreObfuscationEvent;
import dev.frost.obfuscator.plugin.PluginLoader;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.config.ConfigLoader;
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
import dev.frost.graph.transform.BuildExecutionSnapshot;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ObfuscationEngine {

    private final ObfuscationConfig config;
    private final List<String> cliTransformers;
    private final BuildCancellation cancellation;
    private final List<ClassPool.ClassProgressListener> classProgressListeners = new CopyOnWriteArrayList<>();
    private volatile BuildExecutionSnapshot lastBuildGraph;

    public ObfuscationEngine(ObfuscationConfig config, List<String> cliTransformers) {
        this(config, cliTransformers, new BuildCancellation());
    }

    public ObfuscationEngine(ObfuscationConfig config, List<String> cliTransformers,
                             BuildCancellation cancellation) {
        this.config = config;
        this.cliTransformers = cliTransformers;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public ObfuscationEngine addClassProgressListener(ClassPool.ClassProgressListener listener) {
        classProgressListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public ProtectionStats run() throws IOException {
        cancellation.throwIfCancelled();
        long startTime = System.currentTimeMillis();
        Path inputPath = Path.of(config.getInput());
        Path outputPath = Path.of(config.getOutput());
        ProtectionStats stats = new ProtectionStats();
        BuildGraphRecorder graphRecorder = null;
        boolean outputWritten = false;

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
        if (!config.getLibraries().getPaths().isEmpty()) {
            Logger.info("Library paths: {}", config.getLibraries().getPaths());
        }
        Logger.info("Library mode: recursive={} runtime={} strict={} auto-detect={}",
                config.getLibraries().isRecursive(),
                config.getLibraries().isRuntime(),
                config.getLibraries().isStrict(),
                config.getLibraries().isAutoDetect());
        if (config.getInclusions() != null && !config.getInclusions().isEmpty()) {
            Logger.info("Inclusions: {}", config.getInclusions());
        }
        Logger.info("");

        JarProcessor processor = new JarProcessor(cancellation);
        ClassPool pool = processor.loadJar(inputPath);
        pool.setCancellation(cancellation);
        classProgressListeners.forEach(pool::addProgressListener);
        cancellation.throwIfCancelled();
        ObfuscationConfig.PerformanceConfig performance = config.getPerformance();
        pool.configureParallelism(performance.isParallel(), performance.getParallelism(),
                performance.getMinimumClasses());
        stats.set("transformParallelism", pool.transformParallelism());
        Logger.info("Class transformation mode: {} (parallelism={}, minimum classes={})",
                pool.isParallelTransformEnabled() ? "parallel" : "sequential",
                pool.transformParallelism(), performance.getMinimumClasses());
        stats.set("classes", pool.size());

        try {

        PreObfuscationEvent preEvent = PluginLoader.globalEventBus().post(
                new PreObfuscationEvent(inputPath, pool.getClassMap(), processor.getResources(), Map.of())
        );
        if (preEvent != null && preEvent.isCancelled()) {
            Logger.warn("PreObfuscationEvent was cancelled by a plugin. Aborting obfuscation pass.");
            return stats;
        }

        LibraryLoadReport libraryReport = processor.loadLibraries(pool, libraryOptions());
        cancellation.throwIfCancelled();
        stats.set("libraryClasses", libraryReport.loadedClasses());
        stats.set("libraryRuntimeClasses", libraryReport.runtimeClasses());
        stats.set("libraryArchives", libraryReport.libraryArchives().size());
        stats.set("libraryDuplicates", libraryReport.duplicateClasses());
        stats.set("libraryAppShadowedClasses", libraryReport.appShadowedClasses());
        stats.set("libraryExcludedInputClasses", libraryReport.excludedInputClasses());
        stats.set("libraryProblems", libraryReport.problems().size());

        if (config.getLibraries().isAutoDetect()) {
            ApplicationClassDetector.DetectionResult detection = new ApplicationClassDetector()
                    .detect(pool, processor.getDetectedEntrypoints());
            stats.set("autoDetectedLibraryClasses", detection.excludedClasses());
            if (detection.excludedClasses() > 0) {
                Logger.info("Application detection kept {} owned classes and skipped {} embedded library classes "
                                + "across {} package families (roots: {})",
                        pool.transformableSize(), detection.excludedClasses(),
                        detection.excludedFamilies().size(), detection.ownershipRoots());
            }
        }
        stats.set("transformableClasses", pool.transformableSize());
        stats.set("transformationExcludedClasses", pool.transformationExcludedSize());
        int compactedLibraries = processor.compactTransformationExcludedClasses(pool);
        stats.set("compactedLibraryClasses", compactedLibraries);
        if (compactedLibraries > 0) {
            Logger.info("Released bytecode bodies for {} preserved library classes before transformation",
                    compactedLibraries);
        }

        List<String> exclusions = new ArrayList<>(config.getExclusions() != null ? config.getExclusions() : List.of());
        if (config.getPresets() != null && !config.getPresets().isEmpty()) {
            dev.frost.obfuscator.config.preset.ExclusionPresetRegistry presetRegistry =
                    new dev.frost.obfuscator.config.preset.ExclusionPresetRegistry(config.getPresets());
            List<String> presetExclusions = presetRegistry.getCombinedPackageExclusions();
            exclusions.addAll(presetExclusions);
            Logger.info("Applied {} preset(s): {} (expanded {} package pattern exclusions)",
                    presetRegistry.getActivePresets().size(),
                    config.getPresets(),
                    presetExclusions.size());

            for (ClassNode node : pool.getClasses()) {
                if (presetRegistry.isClassExcludedByPreset(node)) {
                    exclusions.add(node.name.replace('/', '.'));
                }
            }
        }

        String detectedMainClass = processor.getDetectedMainClass();
        String mainClassInternal = null;
        String manifestMainClass = processor.getManifestMainClass();
        String manifestMainClassInternal = null;
        if (detectedMainClass != null) {
            mainClassInternal = detectedMainClass.replace('.', '/');
            Logger.info("Detected main class / entrypoint: {} (will be renamed if obfuscated)", detectedMainClass);
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
        graphRecorder = new BuildGraphRecorder(allTransformers, config);
        List<Transformer> preObfuscation = new ArrayList<>();
        List<Transformer> preRename = new ArrayList<>();
        List<Transformer> normal = new ArrayList<>();
        List<Transformer> postFlow = new ArrayList<>();
        List<Transformer> postRemap = new ArrayList<>();
        List<Transformer> finalPass = new ArrayList<>();
        List<Transformer> classloaderEncryption = new ArrayList<>();
        for (Transformer t : allTransformers) {
            switch (t.priority()) {
                case PRE_OBFUSCATION -> preObfuscation.add(t);
                case PRE_RENAME -> preRename.add(t);
                case POST_FLOW -> postFlow.add(t);
                case POST_REMAP -> postRemap.add(t);
                case FINAL -> finalPass.add(t);
                case CLASSLOADER_ENCRYPTION -> classloaderEncryption.add(t);
                default -> normal.add(t);
            }
        }
        for (List<Transformer> group : List.of(preObfuscation, preRename, normal, postFlow,
                postRemap, finalPass, classloaderEncryption)) {
            group.sort(Comparator.comparingInt(Transformer::orderWeight));
        }

        if (!preObfuscation.isEmpty()) {
            Logger.info("Pass 0: Pre-obfuscation generation");
            for (Transformer transformer : preObfuscation) {
                cancellation.throwIfCancelled();
                TransformerConfig tc = resolveConfig(transformer);
                Logger.info("Running transformer: {}", transformer.getName());
                executeTransformer(transformer, tc, pool, processor, mappings, stats, inputPath, outputPath, graphRecorder);
            }
            Logger.info("");
        }

        pool.buildHierarchy();
        Logger.info("Class hierarchy built ({} transformable + {} preserved input libraries + {} support classes)",
                pool.transformableSize(), pool.transformationExcludedSize(), pool.librarySize());

        Logger.info("Active transformers: {}", allTransformers.stream().map(Transformer::getName).toList());
        Logger.info("");

        Logger.info("Pass 1: Collecting mappings");
        for (Transformer transformer : preRename) {
            cancellation.throwIfCancelled();
            TransformerConfig tc = resolveConfig(transformer);
            Logger.info("Running pre-rename transformer: {}", transformer.getName());
            executeTransformer(transformer, tc, pool, processor, mappings, stats, inputPath, outputPath, graphRecorder);
        }
        for (Transformer transformer : normal) {
            cancellation.throwIfCancelled();
            TransformerConfig tc = resolveConfig(transformer);
            Logger.info("Running transformer: {}", transformer.getName());
            executeTransformer(transformer, tc, pool, processor, mappings, stats, inputPath, outputPath, graphRecorder);
        }
        for (Transformer transformer : postFlow) {
            cancellation.throwIfCancelled();
            TransformerConfig tc = resolveConfig(transformer);
            Logger.info("Running post-flow transformer: {}", transformer.getName());
            executeTransformer(transformer, tc, pool, processor, mappings, stats, inputPath, outputPath, graphRecorder);
        }

        Logger.info("");
        Logger.info("Pass 2: Applying remapping");
        Logger.info("Total mappings collected: {}", mappings.totalMappings());

        applyRemapping(pool, mappings);
        cancellation.throwIfCancelled();
        processor.updateRuntimeChecksumClasses(mappings);
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
        if (processor.isFabricMod()) {
            processor.updateFabricModJson(mappings);
        }

        if (!postRemap.isEmpty()) {
            Logger.info("");
            Logger.info("Pass 3: Post-remap transforms");

            for (Transformer transformer : postRemap) {
                cancellation.throwIfCancelled();
                TransformerConfig tc = resolveConfig(transformer);
                Logger.info("Running transformer: {}", transformer.getName());
                executeTransformer(transformer, tc, pool, processor, mappings, stats, inputPath, outputPath, graphRecorder);
            }
        }

        if (!finalPass.isEmpty()) {
            Logger.info("");
            Logger.info("Pass 4: Final transforms");

            for (Transformer transformer : finalPass) {
                cancellation.throwIfCancelled();
                TransformerConfig tc = resolveConfig(transformer);
                Logger.info("Running transformer: {}", transformer.getName());
                executeTransformer(transformer, tc, pool, processor, mappings, stats, inputPath, outputPath, graphRecorder);
            }
        }

        if (config.getFrostJNI() != null && config.getFrostJNI().isEnabled()) {
            cancellation.throwIfCancelled();
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
            cancellation.throwIfCancelled();
            exportMappings(mappings, config.getMapping());
        }

        stats.set("classMappings", mappings.getClassMappings().size());
        stats.set("fieldMappings", mappings.getFieldMappings().size());
        stats.set("methodMappings", mappings.getMethodMappings().size());
        stats.set("totalMappings", mappings.totalMappings());

        if (!classloaderEncryption.isEmpty()) {
            Logger.info("");
            Logger.info("Pass 6: ClassLoader Encryption");

            for (Transformer transformer : classloaderEncryption) {
                cancellation.throwIfCancelled();
                TransformerConfig tc = resolveConfig(transformer);
                Logger.info("Running transformer: {}", transformer.getName());
                executeTransformer(transformer, tc, pool, processor, mappings, stats, inputPath, outputPath, graphRecorder);
            }
        }

        PostObfuscationEvent postEvent = PluginLoader.globalEventBus().post(
                new PostObfuscationEvent(outputPath, Map.of(), processor.getResources())
        );
        if (postEvent != null && postEvent.isCancelled()) {
            Logger.warn("PostObfuscationEvent was cancelled by a plugin. Output JAR writing aborted.");
            return stats;
        }

        cancellation.throwIfCancelled();
        processor.writeJar(pool, outputPath);
        outputWritten = true;
        cancellation.throwIfCancelled();

        long elapsed = System.currentTimeMillis() - startTime;
        stats.set("inputBytes", Files.size(inputPath));
        stats.set("outputBytes", Files.size(outputPath));
        stats.set("elapsedMs", elapsed);
        Logger.info("");
        Logger.info("===================================================");
        Logger.info("  Protection run completed in {}ms", elapsed);
        Logger.info("  Classes: {} transformed / {} preserved | Mappings: {}",
                pool.transformableSize(), pool.transformationExcludedSize(), mappings.totalMappings());
        Logger.info("===================================================");
        return stats;
        } finally {
            if (graphRecorder != null) {
                lastBuildGraph = graphRecorder.snapshot(stats.counters(), outputWritten);
            }
            pool.releaseBuildState();
            processor.releaseBuildState();
        }
    }

    /** The compact, bytecode-free execution snapshot from the most recent run. */
    public Optional<BuildExecutionSnapshot> lastBuildGraph() {
        return Optional.ofNullable(lastBuildGraph);
    }

    private static void executeTransformer(Transformer transformer, TransformerConfig config, ClassPool pool,
                                           JarProcessor processor, MappingCollector mappings, ProtectionStats stats,
                                           Path inputPath, Path outputPath, BuildGraphRecorder recorder) {
        BuildGraphRecorder.Measurement before = recorder.begin(pool, mappings, stats);
        long started = System.nanoTime();
        Throwable failure = null;
        try (ClassPool.ProgressSubscription ignored = pool.transformerProgressScope(transformer.graphId())) {
            transformer.transform(new Context(pool, processor, mappings, config, stats, inputPath, outputPath));
        } catch (RuntimeException | Error throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            recorder.complete(transformer, config, before, pool, mappings, stats,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), failure);
        }
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

    private void exportMappings(MappingCollector mappings, ObfuscationConfig.MappingConfig mappingConfig)
            throws IOException {
        Path output = Path.of(mappingConfig.getOutput());
        if (!mappingConfig.isEncrypted()) {
            mappings.exportMappings(output);
            return;
        }

        if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".enc")) {
            output = output.resolveSibling(output.getFileName() + ".enc");
        }
        char[] password = mappingConfig.getPassword();
        if (password == null || password.length == 0) {
            String variable = mappingConfig.getPasswordEnvironment();
            String environmentPassword = System.getenv(variable);
            if (environmentPassword != null) password = environmentPassword.toCharArray();
        }
        if (password == null || password.length == 0) {
            throw new IOException("Encrypted mapping export requires a password. Set it in the desktop "
                    + "Resources page or provide the " + mappingConfig.getPasswordEnvironment()
                    + " environment variable.");
        }
        try {
            mappings.exportEncryptedMappings(output, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private LibraryOptions libraryOptions() {
        List<Path> paths = ConfigLoader.combinedLibraryPaths(config).stream()
                .map(Path::of)
                .toList();
        return new LibraryOptions(
                paths,
                config.getLibraries().isRecursive(),
                config.getLibraries().isRuntime(),
                config.getLibraries().isStrict()
        );
    }

    private void applyRemapping(ClassPool pool, MappingCollector mappings) {
        FrostRemapper remapper = new FrostRemapper(mappings);
        int totalMappings = mappings.totalMappings();
        Map<String, ClassNode> remappedClasses = new LinkedHashMap<>();
        Map<String, ClassNode> preservedClasses = new LinkedHashMap<>();
        Map<ClassNode, String> originalNames = new IdentityHashMap<>();
        pool.getClassMap().forEach((name, node) -> {
            originalNames.put(node, name);
            if (pool.isTransformationExcluded(name)) preservedClasses.put(name, node);
        });

        List<RemappedClass> results = pool.mapClasses(original -> {
            String oldName = originalNames.get(original);
            ClassNode remapped = new ClassNode();
            ClassRemapper classRemapper = remapper.createClassRemapper(remapped);
            original.accept(classRemapper);
            boolean dirty = !remapped.name.equals(oldName)
                    || mappings.hasAnyMappingForClass(oldName) || totalMappings > 0;
            return new RemappedClass(oldName, remapped, dirty);
        });

        results.stream().sorted(Comparator.comparing(result -> result.node().name)).forEach(result -> {
            remappedClasses.put(result.node().name, result.node());
            pool.setOriginalName(result.node().name, result.originalName());
            if (result.dirty()) pool.markDirty(result.node().name);
        });

        preservedClasses.forEach(remappedClasses::putIfAbsent);

        pool.getClassMap().clear();
        remappedClasses.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> pool.getClassMap().put(entry.getKey(), entry.getValue()));

        Logger.info("Remapping applied to {} classes; {} library classes preserved byte-for-byte",
                results.size(), preservedClasses.size());
    }

    private record RemappedClass(String originalName, ClassNode node, boolean dirty) {
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
