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
import dev.frost.obfuscator.graph.GraphService;
import dev.frost.obfuscator.remapper.MappingFormat;
import dev.frost.graph.*;
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
        description = "Java obfuscation toolkit",
        subcommands = {Main.GraphCommand.class}
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

    @CommandLine.Option(names = {"--auto-detect-libraries"}, arity = "0..1", fallbackValue = "true",
            description = "Keep detected shaded and supplied library classes out of transformation passes")
    private Boolean autoDetectLibraries;

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

    @CommandLine.Option(names = {"-p", "--presets", "--preset"}, split = ",", description = "Exclusion presets (e.g. spigot, fabric, forge, gson, jackson, spring, jpa, sponge)")
    private List<String> presets = new ArrayList<>();

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

    @CommandLine.Option(names = {"--export-mapping-format", "--mapping-format"},
            description = "Mapping export format: yaml, proguard, or tiny")
    private String mappingFormat;

    @CommandLine.Option(names = {"--mapping-encrypted"}, arity = "0..1", fallbackValue = "true",
            description = "Encrypt mapping output with AES-256 (password is read from an environment variable)")
    private Boolean mappingEncrypted;

    @CommandLine.Option(names = {"--mapping-password-env"},
            description = "Environment variable containing the encrypted mapping password")
    private String mappingPasswordEnvironment;

    @CommandLine.Option(names = {"--parallel"}, arity = "0..1", fallbackValue = "true",
            description = "Enable or disable parallel class transformation")
    private Boolean parallelTransforms;

    @CommandLine.Option(names = {"--parallelism"}, description = "Transformer worker count (0 = available processors)")
    private Integer parallelism;

    @CommandLine.Option(names = {"--parallel-min-classes"}, description = "Minimum class count before parallel execution")
    private Integer parallelMinimumClasses;

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

    @CommandLine.Option(names = {"--jni-exclude-method"}, split = ",", description = "FrostJNI method exclusion. Can be repeated or comma-separated.")
    private List<String> jniExcludeMethods = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-exclude-annotation"}, split = ",", description = "FrostJNI annotation exclusion. Can be repeated or comma-separated.")
    private List<String> jniExcludeAnnotations = new ArrayList<>();

    @CommandLine.Option(names = {"--jni-compiler"}, split = ",", description = "Allowed FrostJNI compilers: clang,gcc,msvc")
    private List<String> jniCompilers = new ArrayList<>();

    @CommandLine.Option(names = "--graph-pipeline", description = "Export the pre-build transformer pipeline graph")
    private String graphPipelineOutput;

    @CommandLine.Option(names = "--graph-build", description = "Export the completed build execution graph")
    private String graphBuildOutput;

    @CommandLine.Option(names = "--graph-format", defaultValue = "json", description = "Graph format: json, mermaid, dot")
    private String graphFormat;

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

            GraphService graphs = new GraphService();
            if (graphPipelineOutput != null && !graphPipelineOutput.isBlank()) {
                graphs.export(graphs.transformerGraph("pipeline", config, cliTransformers, GraphOptions.defaults()),
                        graphFormat, Path.of(graphPipelineOutput));
            }
            ObfuscationEngine engine = new ObfuscationEngine(config, cliTransformers);
            engine.run();
            if (graphBuildOutput != null && !graphBuildOutput.isBlank() && engine.lastBuildGraph().isPresent()) {
                graphs.export(graphs.completedBuildGraph(engine.lastBuildGraph().orElseThrow(), GraphOptions.defaults()),
                        graphFormat, Path.of(graphBuildOutput));
            }

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

    @CommandLine.Command(name = "graph", mixinStandardHelpOptions = true,
            description = "Generate a renderer-neutral graph from a JAR or transformer configuration")
    static final class GraphCommand implements Callable<Integer> {
        @CommandLine.Option(names = {"-i", "--input"}, description = "Input JAR") String input;
        @CommandLine.Option(names = {"-o", "--output"}, required = true, description = "Output graph file") String output;
        @CommandLine.Option(names = "--type", required = true,
                description = "dependencies, calls, inheritance, packages, cfg, pipeline, transformers, mappings, build") String type;
        @CommandLine.Option(names = "--format", defaultValue = "json", description = "json, mermaid, dot") String format;
        @CommandLine.Option(names = {"-c", "--config"}, description = "YAML config file") String config;
        @CommandLine.Option(names = "--class", description = "CFG class (binary or internal name)") String className;
        @CommandLine.Option(names = "--method", description = "CFG method name") String method;
        @CommandLine.Option(names = "--descriptor", description = "CFG JVM method descriptor") String descriptor;
        @CommandLine.Option(names = "--lib", split = ",", description = "Dependency archive or directory") List<Path> libraries = new ArrayList<>();
        @CommandLine.Option(names = "--include-libraries", description = "Include external/library nodes") boolean includeLibraries;
        @CommandLine.Option(names = "--max-nodes", defaultValue = "600") int maxNodes;
        @CommandLine.Option(names = "--max-edges", defaultValue = "1800") int maxEdges;
        @CommandLine.Option(names = "--depth", defaultValue = "2") int depth;
        @CommandLine.Option(names = "--focus", description = "Focus node id, label, class, or owner") String focus;
        @CommandLine.Option(names = "--direction", defaultValue = "BOTH",
                description = "Focused traversal: OUTGOING, INCOMING, or BOTH") TraversalDirection direction;

        @Override public Integer call() {
            try {
                GraphService service = new GraphService();
                GraphOptions options = new GraphOptions(maxNodes, maxEdges, depth, includeLibraries,
                        "packages".equalsIgnoreCase(type), false, Set.of(), Set.of(), focus, direction);
                Graph graph;
                if (Set.of("pipeline", "preview", "configuration", "transformers", "mappings", "build").contains(type.toLowerCase(Locale.ROOT))) {
                    ObfuscationConfig loaded = config == null ? ConfigLoader.loadDefault() : ConfigLoader.load(Path.of(config));
                    graph = service.transformerGraph(type, loaded, null, options);
                } else {
                    if (input == null || input.isBlank()) throw new IllegalArgumentException("-i/--input is required for " + type);
                    graph = service.bytecodeGraph(type, service.load(Path.of(input), libraries), className, method,
                            descriptor, options, GraphCancellation.NONE,
                            (done, total, message) -> { if (done == total || done % 250 == 0) Logger.info("Graph: {} ({}/{})", message, done, total); });
                }
                service.export(graph, format, Path.of(output));
                Logger.info("{} graph exported to {} ({} nodes, {} edges{})", type, output,
                        graph.nodes().size(), graph.edges().size(), graph.truncated() ? ", truncated" : "");
                graph.warnings().forEach(warning -> Logger.warn("Graph warning [{}]: {}", warning.code(), warning.message()));
                return 0;
            } catch (Exception exception) {
                Logger.error("Graph generation failed: {}", exception.getMessage());
                return 1;
            }
        }
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
        addAll(config.getPresets(), presets);
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
        if (mappingFormat != null && !mappingFormat.isBlank()) {
            MappingFormat format = MappingFormat.parse(mappingFormat);
            config.getMapping().setEnabled(true);
            config.getMapping().setFormat(format.id());
            if (mappingOutput == null || mappingOutput.isBlank()) {
                config.getMapping().setOutput(format.defaultFileName());
            }
        }
        if (mappingEncrypted != null) {
            config.getMapping().setEncrypted(mappingEncrypted);
        }
        if (mappingPasswordEnvironment != null && !mappingPasswordEnvironment.isBlank()) {
            config.getMapping().setPasswordEnvironment(mappingPasswordEnvironment);
        }
        if (parallelTransforms != null) {
            config.getPerformance().setParallel(parallelTransforms);
        }
        if (parallelism != null) {
            config.getPerformance().setParallelism(Math.max(0, parallelism));
        }
        if (parallelMinimumClasses != null) {
            config.getPerformance().setMinimumClasses(Math.max(1, parallelMinimumClasses));
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
        if (autoDetectLibraries != null) {
            config.setAutoDetect(autoDetectLibraries);
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
        addAll(config.getExcludedMethods(), jniExcludeMethods);
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
        Logger.info("Library mode: recursive={} runtime={} strict={} auto-detect={}",
                config.getLibraries().isRecursive(),
                config.getLibraries().isRuntime(),
                config.getLibraries().isStrict(),
                config.getLibraries().isAutoDetect());
        Logger.info("Mapping: {} {} -> {}{}", config.getMapping().isEnabled(), config.getMapping().getFormat(),
                config.getMapping().getOutput(),
                config.getMapping().isEncrypted() ? " (AES-256 encrypted)" : "");
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
