package dev.frost.obfuscator.config;

import java.util.ArrayList;
import java.util.List;

public class FrostJNIConfig {
    private boolean enabled;
    private String outputLibraryName = "frostjni_protected";
    private String windowsDllName = "frostjni_protected.dll";
    private String linuxSoName = "libfrostjni_protected.so";
    private String macDylibName = "libfrostjni_protected.dylib";
    private boolean useGcc = true;
    private boolean useClang = true;
    private boolean useMsvc = true;
    private String mode = "SELECTIVE";
    private String compileMode = "FAST";
    private boolean unityBuild = true;
    private String optimizationLevel = "O0";
    private boolean stripSymbols;
    private boolean compressLibrary;
    private boolean generateHeaders;
    private List<String> includeClasses = new ArrayList<>();
    private List<String> includePackages = new ArrayList<>();
    private List<String> includeMethods = new ArrayList<>();
    private List<String> includeAnnotations = new ArrayList<>();
    private List<String> excludedClasses = new ArrayList<>();
    private List<String> excludedPackages = new ArrayList<>();
    private List<String> excludedAnnotations = new ArrayList<>();
    private String temporaryDirectory = "";
    private boolean keepGeneratedSources;
    private String loaderMode = "embedded";
    private boolean resourceEmbedding = true;
    private boolean debugMode;
    private boolean failFast = true;
    private boolean continueOnFailure;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOutputLibraryName() {
        return outputLibraryName;
    }

    public void setOutputLibraryName(String outputLibraryName) {
        this.outputLibraryName = blankDefault(outputLibraryName, "frostjni_protected");
    }

    public String getWindowsDllName() {
        return windowsDllName;
    }

    public void setWindowsDllName(String windowsDllName) {
        this.windowsDllName = blankDefault(windowsDllName, "frostjni_protected.dll");
    }

    public String getLinuxSoName() {
        return linuxSoName;
    }

    public void setLinuxSoName(String linuxSoName) {
        this.linuxSoName = blankDefault(linuxSoName, "libfrostjni_protected.so");
    }

    public String getMacDylibName() {
        return macDylibName;
    }

    public void setMacDylibName(String macDylibName) {
        this.macDylibName = blankDefault(macDylibName, "libfrostjni_protected.dylib");
    }

    public boolean isUseGcc() {
        return useGcc;
    }

    public void setUseGcc(boolean useGcc) {
        this.useGcc = useGcc;
    }

    public boolean isUseClang() {
        return useClang;
    }

    public void setUseClang(boolean useClang) {
        this.useClang = useClang;
    }

    public boolean isUseMsvc() {
        return useMsvc;
    }

    public void setUseMsvc(boolean useMsvc) {
        this.useMsvc = useMsvc;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = blankDefault(mode, "SELECTIVE").toUpperCase();
    }

    public String getCompileMode() {
        return compileMode;
    }

    public void setCompileMode(String compileMode) {
        this.compileMode = blankDefault(compileMode, "FAST").toUpperCase();
    }

    public boolean isUnityBuild() {
        return unityBuild;
    }

    public void setUnityBuild(boolean unityBuild) {
        this.unityBuild = unityBuild;
    }

    public String getOptimizationLevel() {
        return optimizationLevel;
    }

    public void setOptimizationLevel(String optimizationLevel) {
        this.optimizationLevel = blankDefault(optimizationLevel, "O2");
    }

    public boolean isStripSymbols() {
        return stripSymbols;
    }

    public void setStripSymbols(boolean stripSymbols) {
        this.stripSymbols = stripSymbols;
    }

    public boolean isCompressLibrary() {
        return compressLibrary;
    }

    public void setCompressLibrary(boolean compressLibrary) {
        this.compressLibrary = compressLibrary;
    }

    public boolean isGenerateHeaders() {
        return generateHeaders;
    }

    public void setGenerateHeaders(boolean generateHeaders) {
        this.generateHeaders = generateHeaders;
    }

    public List<String> getIncludeClasses() {
        return includeClasses;
    }

    public void setIncludeClasses(List<String> includeClasses) {
        this.includeClasses = safeList(includeClasses);
    }

    public List<String> getIncludePackages() {
        return includePackages;
    }

    public void setIncludePackages(List<String> includePackages) {
        this.includePackages = safeList(includePackages);
    }

    public List<String> getIncludeMethods() {
        return includeMethods;
    }

    public void setIncludeMethods(List<String> includeMethods) {
        this.includeMethods = safeList(includeMethods);
    }

    public List<String> getIncludeAnnotations() {
        return includeAnnotations;
    }

    public void setIncludeAnnotations(List<String> includeAnnotations) {
        this.includeAnnotations = safeList(includeAnnotations);
    }

    public List<String> getExcludedClasses() {
        return excludedClasses;
    }

    public void setExcludedClasses(List<String> excludedClasses) {
        this.excludedClasses = safeList(excludedClasses);
    }

    public List<String> getExcludedPackages() {
        return excludedPackages;
    }

    public void setExcludedPackages(List<String> excludedPackages) {
        this.excludedPackages = safeList(excludedPackages);
    }

    public List<String> getExcludedAnnotations() {
        return excludedAnnotations;
    }

    public void setExcludedAnnotations(List<String> excludedAnnotations) {
        this.excludedAnnotations = safeList(excludedAnnotations);
    }

    public String getTemporaryDirectory() {
        return temporaryDirectory;
    }

    public void setTemporaryDirectory(String temporaryDirectory) {
        this.temporaryDirectory = temporaryDirectory == null ? "" : temporaryDirectory;
    }

    public boolean isKeepGeneratedSources() {
        return keepGeneratedSources;
    }

    public void setKeepGeneratedSources(boolean keepGeneratedSources) {
        this.keepGeneratedSources = keepGeneratedSources;
    }

    public String getLoaderMode() {
        return loaderMode;
    }

    public void setLoaderMode(String loaderMode) {
        this.loaderMode = blankDefault(loaderMode, "embedded");
    }

    public boolean isResourceEmbedding() {
        return resourceEmbedding;
    }

    public void setResourceEmbedding(boolean resourceEmbedding) {
        this.resourceEmbedding = resourceEmbedding;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public boolean isContinueOnFailure() {
        return continueOnFailure;
    }

    public void setContinueOnFailure(boolean continueOnFailure) {
        this.continueOnFailure = continueOnFailure;
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
