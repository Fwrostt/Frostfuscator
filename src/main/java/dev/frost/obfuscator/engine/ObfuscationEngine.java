package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.remapper.FrostRemapper;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.TransformerRegistry;
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

        Logger.info("═══════════════════════════════════════════════════");
        Logger.info("  Frostfuscator — Java Bytecode Obfuscator");
        Logger.info("═══════════════════════════════════════════════════");
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
        ClassPool pool = processor.loadJar(Path.of(config.getInput()));

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

        pool.buildHierarchy();
        Logger.info("Class hierarchy built ({} app + {} library classes)",
                pool.size(), pool.librarySize());

        MappingCollector mappings = new MappingCollector();

        List<Transformer> allTransformers = TransformerRegistry.getEnabled(config, cliTransformers);
        List<Transformer> pass1 = new ArrayList<>();
        List<Transformer> postRemap = new ArrayList<>();
        for (Transformer t : allTransformers) {
            if (t.runsPostRemap()) {
                postRemap.add(t);
            } else {
                pass1.add(t);
            }
        }

        Logger.info("Active transformers: {}", allTransformers.stream().map(Transformer::getName).toList());
        Logger.info("");

        Logger.info("══════════ Pass 1: Collecting Mappings ══════════");
        for (Transformer transformer : pass1) {
            TransformerConfig tc = resolveConfig(transformer);
            Logger.info("Running transformer: {}", transformer.getName());
            transformer.transform(pool, mappings, tc);
        }

        Logger.info("");
        Logger.info("══════════ Pass 2: Applying Remapping ══════════");
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
            Logger.info("══════════ Pass 3: Post-Remap Transforms ══════════");

            for (Transformer transformer : postRemap) {
                TransformerConfig tc = resolveConfig(transformer);
                Logger.info("Running transformer: {}", transformer.getName());
                transformer.transform(pool, mappings, tc);
            }
        }

        if (config.getMapping() != null && config.getMapping().isEnabled()) {
            mappings.exportMappings(Path.of(config.getMapping().getOutput()));
        }

        processor.writeJar(pool, Path.of(config.getOutput()));

        long elapsed = System.currentTimeMillis() - startTime;
        Logger.info("");
        Logger.info("═══════════════════════════════════════════════════");
        Logger.info("  Obfuscation completed in {}ms", elapsed);
        Logger.info("  Classes: {} | Mappings: {}", pool.size(), mappings.totalMappings());
        Logger.info("═══════════════════════════════════════════════════");
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
}
