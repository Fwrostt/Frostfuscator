package dev.frost.obfuscator.graph;

import dev.frost.graph.*;
import dev.frost.graph.bytecode.*;
import dev.frost.graph.export.*;
import dev.frost.graph.transform.*;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.transformer.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Headless facade shared by CLI and GUI. */
public final class GraphService {
    private final GraphCache cache = new GraphCache();

    public BytecodeProject load(Path input, Collection<Path> libraries) throws IOException {
        return new JarGraphProjectLoader().load(input, libraries);
    }

    public BytecodeProjectIndex index(BytecodeProject project) {
        return Objects.requireNonNull(project, "project").index();
    }

    public Graph bytecodeGraph(String type, BytecodeProject project, String className, String method,
                               String descriptor, GraphOptions options, GraphCancellation cancellation,
                               GraphProgressListener progress) throws Exception {
        GraphBuildContext context = new GraphBuildContext(options, cancellation, progress, cache);
        return switch (normalize(type)) {
            case "dependencies" -> context.options().aggregatePackages()
                    ? new PackageGraphBuilder().build(project, context)
                    : new ClassDependencyGraphBuilder().build(project, context);
            case "calls" -> new MethodCallGraphBuilder().build(project, context);
            case "inheritance" -> new InheritanceGraphBuilder().build(project, context);
            case "packages" -> new PackageGraphBuilder().build(project, context);
            case "cfg" -> new ControlFlowGraphBuilder().build(
                    new ControlFlowRequest(project, required(className, "class"), required(method, "method"), descriptor), context);
            default -> throw new IllegalArgumentException("Unsupported bytecode graph type: " + type);
        };
    }

    public List<TransformerDescriptor> transformerPlan(ObfuscationConfig config, List<String> override) {
        List<TransformerDescriptor> descriptors = new ArrayList<>();
        int index = 0;
        for (Transformer transformer : TransformerRegistry.getEnabled(config, override)) {
            TransformerConfig item = config.getTransformerConfig(transformer.getName());
            if (item == null) item = new TransformerConfig();
            descriptors.add(new TransformerDescriptor(transformer.graphId(), transformer.getName(), true,
                    transformer.priority().ordinal() * 100_000 + index++, transformer.priority().name(), transformer.dependencies(),
                    transformer.conflicts(), item.getInclusions(), item.getExclusions(), GraphMetadata.builder()
                    .put("category", transformer.getCategory()).put("dictionary", item.getDictionary())
                    .put("options", new TreeMap<>(item.getOptions())).build()));
        }
        return List.copyOf(descriptors);
    }

    public Graph transformerGraph(String type, ObfuscationConfig config, List<String> override, GraphOptions options) {
        List<TransformerDescriptor> plan = transformerPlan(config, override);
        GraphBuildContext context = new GraphBuildContext(options, GraphCancellation.NONE, GraphProgressListener.NONE, cache);
        return switch (normalize(type)) {
            case "pipeline" -> new TransformerPipelineGraphBuilder().build(plan, context);
            case "preview", "configuration" -> new ConfigurationPreviewGraphBuilder().build(plan, context);
            case "transformers" -> new TransformerDependencyGraphBuilder().build(plan, context);
            case "build" -> new BuildExecutionGraphBuilder().build(
                    new BuildExecutionSnapshot("preview", plan, List.of(), GraphMetadata.EMPTY,
                            GraphMetadata.builder().put("preview", true).build()), context);
            case "mappings" -> new MappingGraphBuilder().build(readMappings(config), context);
            default -> throw new IllegalArgumentException("Unsupported transformer graph type: " + type);
        };
    }

    public Graph completedBuildGraph(BuildExecutionSnapshot snapshot, GraphOptions options) {
        return new BuildExecutionGraphBuilder().build(snapshot,
                new GraphBuildContext(options, GraphCancellation.NONE, GraphProgressListener.NONE, cache));
    }

    public Graph obfuscationPreviewGraph(BytecodeProject project, String className,
                                         ObfuscationConfig config, List<String> override,
                                         GraphOptions options) {
        BytecodeClassInfo target = project.index().findClass(required(className, "class"))
                .orElseThrow(() -> new IllegalArgumentException("Class not found: " + className));
        ObfuscationPreviewRequest request = new ObfuscationPreviewRequest(target, transformerPlan(config, override),
                config.getInclusions(), config.getExclusions());
        return new ObfuscationPreviewGraphBuilder().build(request,
                new GraphBuildContext(options, GraphCancellation.NONE, GraphProgressListener.NONE, cache));
    }

    public void export(Graph graph, String format, Path output) throws IOException {
        GraphExporter exporter = GraphExporters.byFormat(format);
        Path destination = output.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(destination, exporter.export(graph), StandardCharsets.UTF_8);
    }

    public void clearCache() { cache.clear(); }

    private static Map<String, String> readMappings(ObfuscationConfig config) {
        Path path = Path.of(config.getMapping().getOutput());
        if (!Files.isRegularFile(path)) return Map.of();
        Map<String, String> mappings = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                String item = line.trim();
                int arrow = item.indexOf(" -> ");
                if (item.isEmpty() || item.startsWith("#") || arrow < 1) continue;
                mappings.put(item.substring(0, arrow).trim(), item.substring(arrow + 4).trim());
            }
        } catch (IOException ignored) { }
        return mappings;
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + name + " is required for cfg graphs");
        return value;
    }
}
