package dev.frost.obfuscator.config;

import dev.frost.obfuscator.transformer.TransformerConfig;

import java.util.*;

public class ObfuscationConfig {

    private String input;
    private String output;
    private String dictionary = "alphabet";
    private List<String> exclusions = new ArrayList<>();
    private List<String> inclusions = new ArrayList<>();
    private List<String> presets = new ArrayList<>();
    private String libs;
    private String packageMode = "keep";
    private String flattenPackage = "obf";
    private long seed;
    private List<String> plugins = new ArrayList<>();
    private LibraryConfig libraries = new LibraryConfig();
    private Map<String, TransformerConfig> transformers = new LinkedHashMap<>();
    private MappingConfig mapping = new MappingConfig();
    private PerformanceConfig performance = new PerformanceConfig();
    private FrostJNIConfig frostJNI = new FrostJNIConfig();

    public ObfuscationConfig() {
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getDictionary() {
        return dictionary;
    }

    public void setDictionary(String dictionary) {
        this.dictionary = dictionary;
    }

    public List<String> getExclusions() {
        return exclusions;
    }

    public void setExclusions(List<String> exclusions) {
        this.exclusions = exclusions != null ? exclusions : new ArrayList<>();
    }

    public List<String> getInclusions() {
        return inclusions;
    }

    public void setInclusions(List<String> inclusions) {
        this.inclusions = inclusions != null ? inclusions : new ArrayList<>();
    }

    public List<String> getPresets() {
        return presets;
    }

    public void setPresets(List<String> presets) {
        this.presets = presets != null ? presets : new ArrayList<>();
    }

    public String getLibs() {
        return libs;
    }

    public void setLibs(String libs) {
        this.libs = libs;
    }

    public String getPackageMode() {
        return packageMode;
    }

    public void setPackageMode(String packageMode) {
        this.packageMode = packageMode;
    }

    public String getFlattenPackage() {
        return flattenPackage;
    }

    public void setFlattenPackage(String flattenPackage) {
        this.flattenPackage = flattenPackage;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public List<String> getPlugins() {
        return plugins;
    }

    public void setPlugins(List<String> plugins) {
        this.plugins = plugins != null ? plugins : new ArrayList<>();
    }

    public LibraryConfig getLibraries() {
        return libraries;
    }

    public void setLibraries(LibraryConfig libraries) {
        this.libraries = libraries != null ? libraries : new LibraryConfig();
    }

    public Map<String, TransformerConfig> getTransformers() {
        return transformers;
    }

    public void setTransformers(Map<String, TransformerConfig> transformers) {
        this.transformers = transformers != null ? transformers : new LinkedHashMap<>();
    }

    public TransformerConfig getTransformerConfig(String name) {
        return transformers.get(name);
    }

    public MappingConfig getMapping() {
        return mapping;
    }

    public void setMapping(MappingConfig mapping) {
        this.mapping = mapping != null ? mapping : new MappingConfig();
    }

    public PerformanceConfig getPerformance() {
        return performance;
    }

    public void setPerformance(PerformanceConfig performance) {
        this.performance = performance != null ? performance : new PerformanceConfig();
    }

    public FrostJNIConfig getFrostJNI() {
        return frostJNI;
    }

    public void setFrostJNI(FrostJNIConfig frostJNI) {
        this.frostJNI = frostJNI != null ? frostJNI : new FrostJNIConfig();
    }

    public static class MappingConfig {
        private boolean enabled = true;
        private String output = "mapping.yml";
        private String format = "yaml";
        private boolean encrypted;
        private String passwordEnvironment = "FROST_MAPPING_PASSWORD";
        private transient char[] password;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getOutput() {
            return output;
        }

        public void setOutput(String output) {
            this.output = output;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format == null || format.isBlank()
                    ? "yaml" : format.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getPasswordEnvironment() {
            return passwordEnvironment;
        }

        public void setPasswordEnvironment(String passwordEnvironment) {
            this.passwordEnvironment = passwordEnvironment == null || passwordEnvironment.isBlank()
                    ? "FROST_MAPPING_PASSWORD" : passwordEnvironment.trim();
        }

        public char[] getPassword() {
            return password == null ? null : password.clone();
        }

        public void setPassword(char[] password) {
            clearPassword();
            this.password = password == null ? null : password.clone();
        }

        public void clearPassword() {
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
                password = null;
            }
        }
    }

    public static class LibraryConfig {
        private List<String> paths = new ArrayList<>();
        private boolean recursive = true;
        private boolean runtime = true;
        private boolean strict;
        private boolean autoDetect = true;

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths != null ? paths : new ArrayList<>();
        }

        public boolean isRecursive() {
            return recursive;
        }

        public void setRecursive(boolean recursive) {
            this.recursive = recursive;
        }

        public boolean isRuntime() {
            return runtime;
        }

        public void setRuntime(boolean runtime) {
            this.runtime = runtime;
        }

        public boolean isStrict() {
            return strict;
        }

        public void setStrict(boolean strict) {
            this.strict = strict;
        }

        public boolean isAutoDetect() {
            return autoDetect;
        }

        public void setAutoDetect(boolean autoDetect) {
            this.autoDetect = autoDetect;
        }
    }

    public static class PerformanceConfig {
        private boolean parallel = true;
        private int parallelism;
        private int minimumClasses = 32;

        public boolean isParallel() {
            return parallel;
        }

        public void setParallel(boolean parallel) {
            this.parallel = parallel;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = Math.max(0, parallelism);
        }

        public int getMinimumClasses() {
            return minimumClasses;
        }

        public void setMinimumClasses(int minimumClasses) {
            this.minimumClasses = Math.max(1, minimumClasses);
        }
    }
}
