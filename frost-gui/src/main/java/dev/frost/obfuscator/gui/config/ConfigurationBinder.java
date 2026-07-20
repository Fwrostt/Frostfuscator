package dev.frost.obfuscator.gui.config;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ConfigWriter;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.analysis.ProjectAnalysis;
import dev.frost.obfuscator.gui.state.ProjectState;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.TransformerRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigurationBinder {
    private final ProjectState state;

    public ConfigurationBinder(ProjectState state) {
        this.state = state;
        ObfuscationConfig initial = ConfigLoader.loadDefault();
        ensureAllTransformers(initial);
        state.replaceConfiguration(initial);
    }

    public void load(Path path) {
        ObfuscationConfig loaded = ConfigLoader.load(path);
        ensureAllTransformers(loaded);
        state.replaceConfiguration(loaded);
        state.profileProperty().set("Custom");
    }

    public void save(Path path) throws IOException {
        ConfigWriter.save(snapshot(), path);
        state.dirtyProperty().set(false);
    }

    public ObfuscationConfig snapshot() {
        return copy(state.configuration());
    }

    public void applyAnalysisSuggestions(ProjectAnalysis analysis) {
        ObfuscationConfig config = state.configuration();
        if ((config.getOutput() == null || config.getOutput().isBlank() || "output.jar".equals(config.getOutput()))
                && !analysis.suggestedOutput().isBlank()) {
            config.setOutput(analysis.suggestedOutput());
        }
        if (config.getFlattenPackage() == null || config.getFlattenPackage().isBlank()
                || "obf".equals(config.getFlattenPackage())) {
            config.setFlattenPackage(analysis.suggestedPackage());
        }
        config.setDictionary(analysis.suggestedDictionary());
        if (config.getExclusions().isEmpty()) config.setExclusions(new ArrayList<>(analysis.exclusions()));
        state.touch();
    }

    public static void ensureAllTransformers(ObfuscationConfig config) {
        for (String name : TransformerRegistry.getAllNames()) {
            TransformerConfig transformer = config.getTransformers().computeIfAbsent(name, key -> new TransformerConfig());
            if (transformer.getDictionary() == null) transformer.setDictionary(config.getDictionary());
        }
    }

    public static ObfuscationConfig copy(ObfuscationConfig source) {
        ObfuscationConfig target = new ObfuscationConfig();
        target.setInput(source.getInput());
        target.setOutput(source.getOutput());
        target.setDictionary(source.getDictionary());
        target.setLibs(source.getLibs());
        target.setPackageMode(source.getPackageMode());
        target.setFlattenPackage(source.getFlattenPackage());
        target.setSeed(source.getSeed());
        target.setPlugins(new ArrayList<>(source.getPlugins()));
        target.setInclusions(new ArrayList<>(source.getInclusions()));
        target.setExclusions(new ArrayList<>(source.getExclusions()));
        ObfuscationConfig.LibraryConfig libraries = new ObfuscationConfig.LibraryConfig();
        libraries.setPaths(new ArrayList<>(source.getLibraries().getPaths()));
        libraries.setRecursive(source.getLibraries().isRecursive());
        libraries.setRuntime(source.getLibraries().isRuntime());
        libraries.setStrict(source.getLibraries().isStrict());
        target.setLibraries(libraries);
        Map<String, TransformerConfig> transformers = new LinkedHashMap<>();
        source.getTransformers().forEach((name, value) -> {
            TransformerConfig copy = new TransformerConfig();
            copy.setEnabled(value.isEnabled());
            copy.setDictionary(value.getDictionary());
            copy.setInclusions(new ArrayList<>(value.getInclusions()));
            copy.setExclusions(new ArrayList<>(value.getExclusions()));
            copy.setOptions(new LinkedHashMap<>(value.getOptions()));
            transformers.put(name, copy);
        });
        target.setTransformers(transformers);
        ObfuscationConfig.MappingConfig mapping = new ObfuscationConfig.MappingConfig();
        mapping.setEnabled(source.getMapping().isEnabled());
        mapping.setOutput(source.getMapping().getOutput());
        target.setMapping(mapping);
        target.setFrostJNI(source.getFrostJNI());
        ensureAllTransformers(target);
        return target;
    }
}
