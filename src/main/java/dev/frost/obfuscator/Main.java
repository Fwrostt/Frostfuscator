package dev.frost.obfuscator;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.FrostJNIConfig;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.config.TransformerProfiles;
import dev.frost.obfuscator.engine.ObfuscationEngine;
import dev.frost.obfuscator.plugin.PluginDescriptor;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.TransformerRegistry;
import dev.frost.obfuscator.util.Logger;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "frostfuscator",
        mixinStandardHelpOptions = true,
        description = "Java obfuscation toolkit"
)
public class Main implements Callable<Integer> {

    @CommandLine.Option(names = {"-i", "--input"}, description = "Input JAR")
    private String input;

    @CommandLine.Option(names = {"-o", "--output"}, description = "Output JAR")
    private String output;

    @CommandLine.Option(names = {"-c", "--config"}, description = "YAML config file")
    private String configPath;

    @CommandLine.Option(names = {"-t", "--transforms", "--transformers"}, description = "Comma-separated transform names; overrides config")
    private String transformersList;

    @CommandLine.Option(names = {"-l", "--libs"}, description = "Folder containing dependency JARs")
    private String libs;

    @CommandLine.Option(names = {"--lib"}, split = ",", description = "Additional library path. Repeat or comma-separate directories/JARs/ZIPs.")
    private List<String> libraryPaths = new ArrayList<>();

    @CommandLine.Option(names = {"--libs-recursive"}, arity = "0..1", fallbackValue = "true", description = "Recursively scan library directories")
    private Boolean librariesRecursive;

    @CommandLine.Option(names = {"--libs-runtime"}, arity = "0..1", fallbackValue = "true", description = "Load Java runtime classes as library stubs")
    private Boolean librariesRuntime;

    @CommandLine.Option(names = {"--libs-strict"}, arity = "0..1", fallbackValue = "true", description = "Fail when library paths or archives cannot be loaded")
    private Boolean librariesStrict;

    @CommandLine.Option(names = {"--plugins"}, split = ",", description = "Plugin directory or comma-separated plugin directories")
    private List<String> pluginDirectories = new ArrayList<>();

    @CommandLine.Option(names = {"--profile"}, description = "Apply a transformer profile: none, basic, balanced, strong, maximum")
    private String profile;

    @CommandLine.Option(names = {"--dictionary"}, description = "Naming dictionary: alphabet, unicode, numeric")
    private String dictionary;

    @CommandLine.Option(names = {"--package-mode"}, description = "Package handling: keep, flatten, remove")
    private String packageMode;

    @CommandLine.Option(names = {"--flatten-package"}, description = "Package name used by --package-mode=flatten")
    private String flattenPackage;

    @CommandLine.Option(names = {"--include"}, split = ",", description = "Global inclusion regex. Can be repeated or comma-separated.")
    private List<String> inclusions = new ArrayList<>();

    @CommandLine.Option(names = {"--exclude"}, split = ",", description = "Global exclusion regex. Can be repeated or comma-separated.")
    private List<String> exclusions = new ArrayList<>();

    @CommandLine.Option(names = {"--list-transforms", "--list-transformers"}, description = "List transforms and exit")
    private boolean listTransformers;

    @CommandLine.Option(names = {"--enable"}, split = ",", description = "Enable transformer names. Can be repeated or comma-separated.")
    private List<String> enableTransformers = new ArrayList<>();

    @CommandLine.Option(names = {"--disable"}, split = ",", description = "Disable transformer names. Can be repeated or comma-separated.")
    private List<String> disableTransformers = new ArrayList<>();

    @CommandLine.Option(names = {"--set"}, split = ",", description = "Set transformer option as transform.key=value. Can be repeated.")
    private List<String> transformerOptions = new ArrayList<>();

    @CommandLine.Option(names = {"--mapping"}, arity = "0..1", fallbackValue = "true", description = "Enable or disable mapping export")
    private Boolean mappingEnabled;

    @CommandLine.Option(names = {"--mapping-output"}, description = "Mapping output path")
    private String mappingOutput;

    @CommandLine.Option(names = {"--report"}, description = "Enable statistics report. Use json:path or html:path.")
    private String report;

    @CommandLine.Option(names = {"--seed"}, description = "Global deterministic seed for seed-aware transformers")
    private Long seed;

    @CommandLine.Option(names = {"--dry-run"}, description = "Validate and print the run plan without writing output")
    private boolean dryRun;

    @CommandLine.Option(names = {"--frostjni"}, arity = "0..1", fallbackValue = "true", description = "Enable or disable FrostJNI")
    private Boolean frostJniEnabled;

    @CommandLine.Option(names = {"--jni-mode"}, description = "FrostJNI mode: SELECTIVE or FULL")
    private String frostJniMode;

    @CommandLine.Option(names = {"--jni-include-package"}, split = ",", description = "FrostJNI package include. Can be repeated or comma-separated.")
    private List<String> jniIncludePackages = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-include-class"}, split = ",", description = "FrostJNI class include. Can be repeated or comma-separated.")
    private List<String> jniIncludeClasses = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-include-method"}, split = ",", description = "FrostJNI method include. Can be repeated or comma-separated.")
    private List<String> jniIncludeMethods = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-include-annotation"}, split = ",", description = "FrostJNI annotation include. Can be repeated or comma-separated.")
    private List<String> jniIncludeAnnotations = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-exclude-package"}, split = ",", description = "FrostJNI package exclusion. Can be repeated or comma-separated.")
    private List<String> jniExcludePackages = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-exclude-class"}, split = ",", description = "FrostJNI class exclusion. Can be repeated or comma-separated.")
    private List<String> jniExcludeClasses = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-exclude-annotation"}, split = ",", description = "FrostJNI annotation exclusion. Can be repeated or comma-separated.")
    private List<String> jniExcludeAnnotations = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-compiler"}, split = ",", description = "Allowed FrostJNI compilers: clang,gcc,msvc")
    private List<String> jniCompilers = new ArrayList<>();

    @Override
    public Integer call() {
        try {
            Logger.printBanner();

            ObfuscationConfig config;
            if (configPath != null) {
                config = ConfigLoader.load(Path.of(configPath));
            } else {
                config = ConfigLoader.loadDefault();
            }

            ConfigLoader.applyOverrides(config, input, output, libs);
            applyCliOptions(config);
            discoverPlugins(config);

            if (listTransformers) {
                Logger.info("Available transforms:");
                for (String name : TransformerRegistry.getAllNames()) {
                    Logger.info("  - {}", name);
                }
                return 0;
            }

            ConfigLoader.validate(config);

            List<String> cliTransformers = null;
            if (transformersList != null && !transformersList.isEmpty()) {
                cliTransformers = Arrays.stream(transformersList.split(","))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .toList();
                for (String transformer : cliTransformers) {
                    if (TransformerRegistry.getByName(transformer) == null) {
                        throw new IllegalArgumentException("Unknown transformer in CLI override: " + transformer);
                    }
                }
                Logger.info("CLI transform override: {}", cliTransformers);
            }

            if (dryRun) {
                printDryRun(config, cliTransformers);
                return 0;
            }

            ObfuscationEngine engine = new ObfuscationEngine(config, cliTransformers);
            engine.run();

            return 0;
        } catch (Exception e) {
            Logger.error("Obfuscation failed: {}", e.getMessage());
            Logger.error("Fatal error", e);
            return 1;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    private void applyCliOptions(ObfuscationConfig config) {
        if (profile != null && !profile.isBlank()) {
            TransformerProfiles.apply(config, profile);
        }
        if (dictionary != null && !dictionary.isBlank()) {
            config.setDictionary(dictionary);
        }
        if (packageMode != null && !packageMode.isBlank()) {
            config.setPackageMode(packageMode);
        }
        if (flattenPackage != null && !flattenPackage.isBlank()) {
            config.setFlattenPackage(flattenPackage);
        }
        addAll(config.getInclusions(), inclusions);
        addAll(config.getExclusions(), exclusions);
        for (String name : clean(enableTransformers)) {
            config.getTransformers().computeIfAbsent(name, key -> new TransformerConfig()).setEnabled(true);
        }
        for (String name : clean(disableTransformers)) {
            config.getTransformers().computeIfAbsent(name, key -> new TransformerConfig()).setEnabled(false);
        }
        for (String option : clean(transformerOptions)) {
            applyTransformerOption(config, option);
        }
        if (mappingEnabled != null) {
            config.getMapping().setEnabled(mappingEnabled);
        }
        if (mappingOutput != null && !mappingOutput.isBlank()) {
            config.getMapping().setOutput(mappingOutput);
        }
        if (report != null && !report.isBlank()) {
            enableReport(config, report);
        }
        if (seed != null) {
            TransformerProfiles.applySeed(config, seed);
        }
        applyFrostJniOptions(config.getFrostJNI());
        applyLibraryOptions(config.getLibraries());
        addAll(config.getPlugins(), pluginDirectories);
    }

    private void applyLibraryOptions(ObfuscationConfig.LibraryConfig config) {
        addAll(config.getPaths(), libraryPaths);
        if (librariesRecursive != null) {
            config.setRecursive(librariesRecursive);
        }
        if (librariesRuntime != null) {
            config.setRuntime(librariesRuntime);
        }
        if (librariesStrict != null) {
            config.setStrict(librariesStrict);
        }
    }

    private void applyFrostJniOptions(FrostJNIConfig config) {
        if (frostJniEnabled != null) {
            config.setEnabled(frostJniEnabled);
        }
        if (frostJniMode != null && !frostJniMode.isBlank()) {
            config.setMode(frostJniMode);
        }
        addAll(config.getIncludePackages(), jniIncludePackages);
        addAll(config.getIncludeClasses(), jniIncludeClasses);
        addAll(config.getIncludeMethods(), jniIncludeMethods);
        addAll(config.getIncludeAnnotations(), jniIncludeAnnotations);
        addAll(config.getExcludedPackages(), jniExcludePackages);
        addAll(config.getExcludedClasses(), jniExcludeClasses);
        addAll(config.getExcludedAnnotations(), jniExcludeAnnotations);
        if (!jniCompilers.isEmpty()) {
            Set<String> selected = new LinkedHashSet<>(clean(jniCompilers).stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList());
            config.setUseClang(selected.contains("clang"));
            config.setUseGcc(selected.contains("gcc") || selected.contains("mingw"));
            config.setUseMsvc(selected.contains("msvc"));
        }
    }

    private void discoverPlugins(ObfuscationConfig config) {
        List<Path> directories = new ArrayList<>();
        directories.add(Path.of("plugins"));
        for (String configured : clean(config.getPlugins())) {
            directories.add(Path.of(configured));
        }
        List<PluginDescriptor> loaded = TransformerRegistry.discoverPlugins(directories);
        if (!loaded.isEmpty()) {
            Logger.info("Plugin discovery loaded: {}", loaded.stream().map(PluginDescriptor::name).toList());
        }
    }

    private void applyTransformerOption(ObfuscationConfig config, String option) {
        int dot = option.indexOf('.');
        int equals = option.indexOf('=');
        if (dot <= 0 || equals <= dot + 1) {
            throw new IllegalArgumentException("--set must use transform.key=value, got: " + option);
        }
        String transformer = option.substring(0, dot);
        String key = option.substring(dot + 1, equals);
        String rawValue = option.substring(equals + 1);
        TransformerConfig transformerConfig = config.getTransformers().computeIfAbsent(transformer, ignored -> new TransformerConfig());
        transformerConfig.getOptions().put(key, parseScalar(rawValue));
    }

    private void enableReport(ObfuscationConfig config, String value) {
        TransformerConfig reportConfig = config.getTransformers().computeIfAbsent("statistics-report", ignored -> new TransformerConfig());
        reportConfig.setEnabled(true);
        String format = value.toLowerCase(Locale.ROOT).startsWith("html:") ? "html" : "json";
        String output = value;
        int colon = value.indexOf(':');
        if (colon > 0) {
            format = value.substring(0, colon);
            output = value.substring(colon + 1);
        } else if (value.toLowerCase(Locale.ROOT).endsWith(".html")) {
            format = "html";
        }
        reportConfig.getOptions().put("format", format);
        reportConfig.getOptions().put("output", output);
    }

    private void printDryRun(ObfuscationConfig config, List<String> cliTransformers) {
        List<Transformer> enabled = TransformerRegistry.getEnabled(config, cliTransformers);
        Logger.info("Dry run: no jar will be written.");
        Logger.info("Input: {}", config.getInput());
        Logger.info("Output: {}", config.getOutput());
        Logger.info("Dictionary: {}", config.getDictionary());
        Logger.info("Package mode: {}", config.getPackageMode());
        Logger.info("Seed: {}", config.getSeed() == 0 ? "fresh randomness" : config.getSeed());
        Logger.info("Inclusions: {}", config.getInclusions());
        Logger.info("Exclusions: {}", config.getExclusions());
        Logger.info("Libraries: {}", ConfigLoader.combinedLibraryPaths(config));
        Logger.info("Library mode: recursive={} runtime={} strict={}",
                config.getLibraries().isRecursive(),
                config.getLibraries().isRuntime(),
                config.getLibraries().isStrict());
        Logger.info("Mapping: {} -> {}", config.getMapping().isEnabled(), config.getMapping().getOutput());
        Logger.info("FrostJNI: {}", config.getFrostJNI().isEnabled());
        Logger.info("Active transformers:");
        for (Transformer transformer : enabled) {
            Logger.info("  - {}", transformer.getName());
        }
    }

    private void addAll(List<String> target, List<String> values) {
        for (String value : clean(values)) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    private List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private Object parseScalar(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }
        return value;
    }
}
