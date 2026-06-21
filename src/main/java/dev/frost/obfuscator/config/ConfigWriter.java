package dev.frost.obfuscator.config;

import dev.frost.obfuscator.transformer.TransformerConfig;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigWriter {

    private ConfigWriter() {
    }

    public static void save(ObfuscationConfig config, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        try (Writer writer = Files.newBufferedWriter(path)) {
            yaml.dump(toMap(config), writer);
        }
    }

    private static Map<String, Object> toMap(ObfuscationConfig config) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("input", nullToEmpty(config.getInput()));
        root.put("output", nullToEmpty(config.getOutput()));
        root.put("dictionary", config.getDictionary());
        root.put("libs", nullToEmpty(config.getLibs()));
        root.put("package-mode", config.getPackageMode());
        root.put("flatten-package", config.getFlattenPackage());
        root.put("inclusions", config.getInclusions());
        root.put("exclusions", config.getExclusions());

        Map<String, Object> transformers = new LinkedHashMap<>();
        for (Map.Entry<String, TransformerConfig> entry : config.getTransformers().entrySet()) {
            TransformerConfig tc = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enabled", tc.isEnabled());
            if (tc.getDictionary() != null) item.put("dictionary", tc.getDictionary());
            item.put("exclusions", tc.getExclusions());
            item.put("inclusions", tc.getInclusions());
            item.putAll(tc.getOptions());
            transformers.put(entry.getKey(), item);
        }
        root.put("transformers", transformers);

        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("enabled", config.getMapping().isEnabled());
        mapping.put("output", config.getMapping().getOutput());
        root.put("mapping", mapping);

        root.put("frostjni", frostJniMap(config.getFrostJNI()));
        return root;
    }

    private static Map<String, Object> frostJniMap(FrostJNIConfig config) {
        Map<String, Object> nativeConfig = new LinkedHashMap<>();
        nativeConfig.put("enabled", config.isEnabled());
        nativeConfig.put("outputLibraryName", config.getOutputLibraryName());
        nativeConfig.put("windowsDllName", config.getWindowsDllName());
        nativeConfig.put("linuxSoName", config.getLinuxSoName());
        nativeConfig.put("macDylibName", config.getMacDylibName());
        nativeConfig.put("useGcc", config.isUseGcc());
        nativeConfig.put("useClang", config.isUseClang());
        nativeConfig.put("useMsvc", config.isUseMsvc());
        nativeConfig.put("mode", config.getMode());
        nativeConfig.put("compileMode", config.getCompileMode());
        nativeConfig.put("unityBuild", config.isUnityBuild());
        nativeConfig.put("optimizationLevel", config.getOptimizationLevel());
        nativeConfig.put("stripSymbols", config.isStripSymbols());
        nativeConfig.put("compressLibrary", config.isCompressLibrary());
        nativeConfig.put("generateHeaders", config.isGenerateHeaders());
        nativeConfig.put("includeClasses", config.getIncludeClasses());
        nativeConfig.put("includePackages", config.getIncludePackages());
        nativeConfig.put("includeMethods", config.getIncludeMethods());
        nativeConfig.put("includeAnnotations", config.getIncludeAnnotations());
        nativeConfig.put("excludedClasses", config.getExcludedClasses());
        nativeConfig.put("excludedPackages", config.getExcludedPackages());
        nativeConfig.put("excludedAnnotations", config.getExcludedAnnotations());
        nativeConfig.put("temporaryDirectory", config.getTemporaryDirectory());
        nativeConfig.put("keepGeneratedSources", config.isKeepGeneratedSources());
        nativeConfig.put("loaderMode", config.getLoaderMode());
        nativeConfig.put("resourceEmbedding", config.isResourceEmbedding());
        nativeConfig.put("debugMode", config.isDebugMode());
        nativeConfig.put("failFast", config.isFailFast());
        nativeConfig.put("continueOnFailure", config.isContinueOnFailure());
        return nativeConfig;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
