package dev.frost.obfuscator.config;

import dev.frost.obfuscator.transformer.TransformerConfig;

import java.util.*;

public class ObfuscationConfig {

    private String input;
    private String output;
    private String dictionary = "alphabet";
    private List<String> exclusions = new ArrayList<>();
    private List<String> inclusions = new ArrayList<>();
    private String libs;
    private String packageMode = "keep";
    private String flattenPackage = "obf";
    private long seed;
    private List<String> plugins = new ArrayList<>();
    private LibraryConfig libraries = new LibraryConfig();
    private Map<String, TransformerConfig> transformers = new LinkedHashMap<>();
    private MappingConfig mapping = new MappingConfig();
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

    public FrostJNIConfig getFrostJNI() {
        return frostJNI;
    }

    public void setFrostJNI(FrostJNIConfig frostJNI) {
        this.frostJNI = frostJNI != null ? frostJNI : new FrostJNIConfig();
    }

    public static class MappingConfig {
        private boolean enabled = true;
        private String output = "mapping.txt";

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
    }

    public static class LibraryConfig {
        private List<String> paths = new ArrayList<>();
        private boolean recursive = true;
        private boolean runtime = true;
        private boolean strict;

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
    }
}
