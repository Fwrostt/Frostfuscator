package dev.frost.obfuscator.config;

import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigLoader {

    public static ObfuscationConfig load(Path configPath) {
        try (InputStream is = Files.newInputStream(configPath)) {
            Logger.info("Loading config from {}", configPath);
            return parse(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config from " + configPath, e);
        }
    }

    public static ObfuscationConfig loadDefault() {
        try (InputStream is = ConfigLoader.class.getResourceAsStream("/config.yml")) {
            if (is == null) {
                Logger.warn("No default config.yml found on classpath, using empty config");
                return new ObfuscationConfig();
            }
            Logger.info("Loading default config from classpath");
            return parse(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load default config", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObfuscationConfig parse(InputStream is) {
        Yaml yaml = new Yaml();
        Map<String, Object> raw = yaml.load(is);

        if (raw == null) {
            return new ObfuscationConfig();
        }

        ObfuscationConfig config = new ObfuscationConfig();

        config.setInput(getString(raw, "input", "input.jar"));
        config.setOutput(getString(raw, "output", "output.jar"));
        config.setDictionary(getString(raw, "dictionary", "alphabet"));
        config.setLibs(getString(raw, "libs", null));
        config.setPackageMode(getString(raw, "package-mode", "keep"));
        config.setFlattenPackage(getString(raw, "flatten-package", "obf"));

        Object exclusionsObj = raw.get("exclusions");
        if (exclusionsObj instanceof List<?> list) {
            config.setExclusions(list.stream().map(Object::toString).toList());
        }

        Object inclusionsObj = raw.get("inclusions");
        if (inclusionsObj instanceof List<?> list) {
            config.setInclusions(list.stream().map(Object::toString).toList());
        }

        Object transformersObj = raw.get("transformers");
        if (transformersObj instanceof Map<?, ?> transformersMap) {
            Map<String, TransformerConfig> transformerConfigs = new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry : transformersMap.entrySet()) {
                String name = entry.getKey().toString();
                TransformerConfig tc = new TransformerConfig();

                if (entry.getValue() instanceof Map<?, ?> tcMap) {
                    tc.setEnabled(getBoolean(tcMap, "enabled", true));

                    Object tcExclusions = tcMap.get("exclusions");
                    if (tcExclusions instanceof List<?> tcExList) {
                        tc.setExclusions(tcExList.stream().map(Object::toString).toList());
                    }

                    Object tcInclusions = tcMap.get("inclusions");
                    if (tcInclusions instanceof List<?> tcInList) {
                        tc.setInclusions(tcInList.stream().map(Object::toString).toList());
                    }

                    String dict = getString(tcMap, "dictionary", null);
                    tc.setDictionary(dict != null ? dict : config.getDictionary());

                    Map<String, Object> options = new java.util.LinkedHashMap<>();
                    for (Map.Entry<?, ?> opt : ((Map<?, ?>) tcMap).entrySet()) {
                        String k = opt.getKey().toString();
                        if (!k.equals("enabled") && !k.equals("exclusions") && !k.equals("inclusions") && !k.equals("dictionary")) {
                            options.put(k, opt.getValue());
                        }
                    }
                    tc.setOptions(options);
                }

                transformerConfigs.put(name, tc);
            }

            config.setTransformers(transformerConfigs);
        }

        Object mappingObj = raw.get("mapping");
        if (mappingObj instanceof Map<?, ?> mappingMap) {
            ObfuscationConfig.MappingConfig mc = new ObfuscationConfig.MappingConfig();
            mc.setEnabled(getBoolean(mappingMap, "enabled", true));
            mc.setOutput(getString(mappingMap, "output", "mapping.txt"));
            config.setMapping(mc);
        }

        Object frostJniObj = raw.get("frostjni");
        if (frostJniObj instanceof Map<?, ?> frostJniMap) {
            FrostJNIConfig nativeConfig = new FrostJNIConfig();
            nativeConfig.setEnabled(getBoolean(frostJniMap, "enabled", false));
            nativeConfig.setOutputLibraryName(getString(frostJniMap, "outputLibraryName", "frostjni_protected"));
            nativeConfig.setWindowsDllName(getString(frostJniMap, "windowsDllName", "frostjni_protected.dll"));
            nativeConfig.setLinuxSoName(getString(frostJniMap, "linuxSoName", "libfrostjni_protected.so"));
            nativeConfig.setMacDylibName(getString(frostJniMap, "macDylibName", "libfrostjni_protected.dylib"));
            nativeConfig.setUseGcc(getBoolean(frostJniMap, "useGcc", true));
            nativeConfig.setUseClang(getBoolean(frostJniMap, "useClang", true));
            nativeConfig.setUseMsvc(getBoolean(frostJniMap, "useMsvc", true));
            nativeConfig.setMode(getString(frostJniMap, "mode", "SELECTIVE"));
            nativeConfig.setCompileMode(getString(frostJniMap, "compileMode", "FAST"));
            nativeConfig.setUnityBuild(getBoolean(frostJniMap, "unityBuild", true));
            nativeConfig.setOptimizationLevel(getString(frostJniMap, "optimizationLevel", "O0"));
            nativeConfig.setStripSymbols(getBoolean(frostJniMap, "stripSymbols", false));
            nativeConfig.setCompressLibrary(getBoolean(frostJniMap, "compressLibrary", false));
            nativeConfig.setGenerateHeaders(getBoolean(frostJniMap, "generateHeaders", true));
            nativeConfig.setIncludeClasses(getStringList(frostJniMap, "includeClasses"));
            nativeConfig.setIncludePackages(getStringList(frostJniMap, "includePackages"));
            nativeConfig.setIncludeMethods(getStringList(frostJniMap, "includeMethods"));
            nativeConfig.setIncludeAnnotations(getStringList(frostJniMap, "includeAnnotations"));
            nativeConfig.setExcludedClasses(getStringList(frostJniMap, "excludedClasses"));
            nativeConfig.setExcludedPackages(getStringList(frostJniMap, "excludedPackages"));
            nativeConfig.setExcludedAnnotations(getStringList(frostJniMap, "excludedAnnotations"));
            nativeConfig.setTemporaryDirectory(getString(frostJniMap, "temporaryDirectory", ""));
            nativeConfig.setKeepGeneratedSources(getBoolean(frostJniMap, "keepGeneratedSources", false));
            nativeConfig.setLoaderMode(getString(frostJniMap, "loaderMode", "embedded"));
            nativeConfig.setResourceEmbedding(getBoolean(frostJniMap, "resourceEmbedding", true));
            nativeConfig.setDebugMode(getBoolean(frostJniMap, "debugMode", false));
            nativeConfig.setFailFast(getBoolean(frostJniMap, "failFast", true));
            nativeConfig.setContinueOnFailure(getBoolean(frostJniMap, "continueOnFailure", false));
            config.setFrostJNI(nativeConfig);
        }

        return config;
    }

    public static void applyOverrides(ObfuscationConfig config, String inputOverride, String outputOverride, String libsOverride) {
        if (inputOverride != null && !inputOverride.isEmpty()) {
            config.setInput(inputOverride);
        }
        if (outputOverride != null && !outputOverride.isEmpty()) {
            config.setOutput(outputOverride);
        }
        if (libsOverride != null && !libsOverride.isEmpty()) {
            config.setLibs(libsOverride);
        }
    }

    public static void validate(ObfuscationConfig config) {
        if (config.getInput() == null || config.getInput().isEmpty()) {
            throw new IllegalArgumentException("Input JAR path is required");
        }
        if (config.getOutput() == null || config.getOutput().isEmpty()) {
            throw new IllegalArgumentException("Output JAR path is required");
        }

        Path inputPath = Path.of(config.getInput());
        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("Input JAR does not exist: " + inputPath);
        }
    }

    private static String getString(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private static boolean getBoolean(Map<?, ?> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return defaultValue;
    }

    private static List<String> getStringList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Arrays.stream(string.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .toList();
        }
        return List.of();
    }
}
