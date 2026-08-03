package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.util.Logger;
import dev.frost.obfuscator.util.ClassFileVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.serializer.NumberAnchorGenerator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class JarProcessor {
    private static final long MAX_POST_WRITE_VERIFIER_FRAME_CELLS = 1_000_000L;

    private static final Object RUNTIME_CACHE_LOCK = new Object();
    private static volatile Map<String, ClassNode> runtimeClassCache;

    /**
     * Releases parsed JDK class stubs after an interactive build becomes idle.
     * CLI processes normally exit after one build, while the desktop application
     * must not retain this archive-sized cache for the rest of its lifetime.
     *
     * @return the number of cached runtime classes released
     */
    public static int releaseRuntimeClassCache() {
        synchronized (RUNTIME_CACHE_LOCK) {
            Map<String, ClassNode> cached = runtimeClassCache;
            runtimeClassCache = null;
            return cached == null ? 0 : cached.size();
        }
    }

    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final Map<String, byte[]> originalClassBytes = new LinkedHashMap<>();
    private final Map<String, byte[]> preFlowClassBytes = new LinkedHashMap<>();
    private final Set<String> runtimeChecksumClasses = new LinkedHashSet<>();
    private Manifest manifest;
    private String detectedMainClass;
    private final Set<String> detectedDescriptorEntrypoints = new LinkedHashSet<>();
    private final Set<String> detectedFabricEntrypoints = new LinkedHashSet<>();
    private final List<String> detectedFabricMixins = new ArrayList<>();
    private boolean isFabricMod = false;
    private final Map<String, NestedJarData> nestedJars = new LinkedHashMap<>();
    private final BuildCancellation cancellation;

    public JarProcessor() {
        this(new BuildCancellation());
    }

    public JarProcessor(BuildCancellation cancellation) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public static class NestedJarData {
        public String resourcePath;
        public Map<String, String> classNameToPath = new LinkedHashMap<>();
        public Map<String, byte[]> innerResources = new LinkedHashMap<>();
        public Manifest innerManifest;
    }

    private boolean isNestedJarEntry(String name) {
        return (name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/") || name.startsWith("META-INF/jars/"))
                && name.endsWith(".jar");
    }

    private boolean isNestedClass(String className) {
        for (NestedJarData data : nestedJars.values()) {
            if (data.classNameToPath.containsKey(className)) {
                return true;
            }
        }
        return false;
    }

    private void unpackNestedJar(String resourcePath, byte[] jarBytes, ClassPool pool) {
        try {
            NestedJarData nestedData = new NestedJarData();
            nestedData.resourcePath = resourcePath;

            try (java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
                nestedData.innerManifest = jis.getManifest();
                java.util.jar.JarEntry entry;
                while ((entry = jis.getNextJarEntry()) != null) {
                    cancellation.throwIfCancelled();
                    byte[] data = jis.readAllBytes();
                    if (entry.getName().endsWith(".class")) {
                        try {
                            ClassFileVersion.requireSupported(data, entry.getName());
                            ClassReader reader = new ClassReader(data);
                            ClassNode classNode = new ClassNode();
                            reader.accept(classNode, ClassReader.EXPAND_FRAMES);
                            originalClassBytes.put(classNode.name, data);
                            pool.addClass(classNode.name, classNode);
                            pool.excludeFromTransformation(classNode.name, "nested library JAR");
                            nestedData.classNameToPath.put(classNode.name, entry.getName());
                        } catch (Exception ignored) {}
                    } else if (!entry.getName().equals("META-INF/MANIFEST.MF")) {
                        nestedData.innerResources.put(entry.getName(), data);
                    }
                }
            }
            if (!nestedData.classNameToPath.isEmpty()) {
                nestedJars.put(resourcePath, nestedData);
                Logger.info("Unpacked nested Fat JAR {} with {} classes", resourcePath, nestedData.classNameToPath.size());
            }
        } catch (java.util.concurrent.CancellationException cancellationException) {
            throw cancellationException;
        } catch (Exception exception) {
            Logger.warn("Failed to unpack nested Fat JAR {}: {}", resourcePath, exception.getMessage());
        }
    }

    private byte[] buildClassBytes(ClassPool pool, ClassNode node) {
        // Nested classes are loaded with EXPAND_FRAMES and may be renamed or structurally
        // transformed before repacking. Always discard expanded snapshots and regenerate a
        // valid StackMapTable rather than relying on COMPUTE_MAXS to preserve them.
        removeStaleFrames(node);
        ClassWriter writer = new HierarchyClassWriter(pool, ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private void repackNestedJars(ClassPool pool) throws IOException {
        for (NestedJarData nestedData : nestedJars.values()) {
            cancellation.throwIfCancelled();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.util.jar.JarOutputStream jos = nestedData.innerManifest != null
                    ? new java.util.jar.JarOutputStream(baos, nestedData.innerManifest)
                    : new java.util.jar.JarOutputStream(baos)) {

                for (Map.Entry<String, String> entry : nestedData.classNameToPath.entrySet()) {
                    cancellation.throwIfCancelled();
                    String className = entry.getKey();
                    String innerPath = entry.getValue();

                    ClassNode node = pool.getClass(className);
                    byte[] classBytes = originalClassBytes.get(className);
                    if (node != null && (pool.isDirty(node.name) || !node.name.equals(className))) {
                        classBytes = buildClassBytes(pool, node);
                    }

                    java.util.jar.JarEntry jarEntry = new java.util.jar.JarEntry(innerPath);
                    jos.putNextEntry(jarEntry);
                    if (classBytes != null) {
                        jos.write(classBytes);
                    }
                    jos.closeEntry();
                }

                for (Map.Entry<String, byte[]> resEntry : nestedData.innerResources.entrySet()) {
                    cancellation.throwIfCancelled();
                    java.util.jar.JarEntry jarEntry = new java.util.jar.JarEntry(resEntry.getKey());
                    jos.putNextEntry(jarEntry);
                    jos.write(resEntry.getValue());
                    jos.closeEntry();
                }
            }
            resources.put(nestedData.resourcePath, baos.toByteArray());
            Logger.info("Repacked nested Fat JAR {}", nestedData.resourcePath);
        }
    }

    public boolean isFabricMod() {
        return isFabricMod;
    }

    public Set<String> getDetectedFabricEntrypoints() {
        return Collections.unmodifiableSet(detectedFabricEntrypoints);
    }

    public List<String> getDetectedFabricMixins() {
        return Collections.unmodifiableList(detectedFabricMixins);
    }

    public Set<String> getDetectedEntrypoints() {
        Set<String> entrypoints = new LinkedHashSet<>();
        if (detectedMainClass != null && !detectedMainClass.isBlank()) entrypoints.add(detectedMainClass);
        String manifestMain = getManifestMainClass();
        if (manifestMain != null && !manifestMain.isBlank()) entrypoints.add(manifestMain);
        entrypoints.addAll(detectedFabricEntrypoints);
        entrypoints.addAll(detectedDescriptorEntrypoints);
        return Collections.unmodifiableSet(entrypoints);
    }

    public Map<String, byte[]> getResources() {
        return resources;
    }

    public void putResource(String name, byte[] data) {
        resources.put(name, data);
    }

    public byte[] removeResource(String name) {
        return resources.remove(name);
    }

    void releaseBuildState() {
        resources.clear();
        originalClassBytes.clear();
        preFlowClassBytes.clear();
        runtimeChecksumClasses.clear();
        detectedDescriptorEntrypoints.clear();
        detectedFabricEntrypoints.clear();
        detectedFabricMixins.clear();
        nestedJars.clear();
        manifest = null;
        detectedMainClass = null;
        isFabricMod = false;
    }

    public Map<String, byte[]> getOriginalClassBytes() {
        return originalClassBytes;
    }

    public void setRuntimeChecksumClasses(Collection<String> classNames) {
        runtimeChecksumClasses.clear();
        if (classNames != null) {
            runtimeChecksumClasses.addAll(classNames);
        }
    }

    public void updateRuntimeChecksumClasses(MappingCollector mappings) {
        if (runtimeChecksumClasses.isEmpty() || mappings == null) return;
        Set<String> remapped = new LinkedHashSet<>();
        for (String className : runtimeChecksumClasses) {
            remapped.add(mappings.getMappedClass(className));
        }
        runtimeChecksumClasses.clear();
        runtimeChecksumClasses.addAll(remapped);
    }

    public ClassPool loadJar(Path inputPath) throws IOException {
        ClassPool pool = new ClassPool();

        try (JarFile jarFile = new JarFile(inputPath.toFile())) {
            manifest = jarFile.getManifest();

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                cancellation.throwIfCancelled();
                JarEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream is = jarFile.getInputStream(entry)) {
                    data = is.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) {
                    ClassFileVersion.requireSupported(data, entry.getName());
                    try {
                        ClassReader reader = new ClassReader(data);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.EXPAND_FRAMES);
                        originalClassBytes.put(classNode.name, data);
                        pool.addClass(classNode.name, classNode);
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("Could not parse " + entry.getName() + " (Java "
                                + ClassFileVersion.javaVersion(ClassFileVersion.major(data))
                                + " bytecode): " + exception.getMessage(), exception);
                    }
                } else if (isNestedJarEntry(entry.getName())) {
                    resources.put(entry.getName(), data);
                    unpackNestedJar(entry.getName(), data, pool);
                } else if (!entry.getName().equals("META-INF/MANIFEST.MF")) {
                    resources.put(entry.getName(), data);
                }
            }
        }

        detectMainClass();

        Logger.info("Loaded {} classes and {} resources from {}",
                pool.size(), resources.size(), inputPath.getFileName());

        if (detectedMainClass != null) {
            Logger.info("Detected plugin main class: {}", detectedMainClass);
        }

        return pool;
    }

    public LibraryLoadReport loadLibraries(ClassPool pool, LibraryOptions options) throws IOException {
        LibraryLoadReport report = new LibraryLoadReport();
        if (options.loadRuntime()) {
            loadRuntimeClasses(pool, report);
        }

        for (Path input : options.paths()) {
            cancellation.throwIfCancelled();
            Path normalized = input.toAbsolutePath().normalize();
            report.scannedInput(normalized);
            if (!Files.exists(normalized)) {
                report.problem(normalized, "library path does not exist", null);
                continue;
            }
            if (Files.isDirectory(normalized)) {
                scanLibraryDirectory(pool, normalized, options.recursive(), report);
            } else if (isArchive(normalized)) {
                loadLibraryArchive(pool, normalized, report);
            } else {
                report.problem(normalized, "not a .jar or .zip archive", null);
            }
        }

        for (LibraryLoadReport.LibraryProblem problem : report.problems()) {
            Logger.warn("Library problem at {}: {}{}",
                    problem.path(),
                    problem.message(),
                    problem.cause() == null ? "" : " (" + problem.cause() + ")");
        }
        Logger.info("Loaded library support: {}", report.summary());
        if (options.strict() && report.hasProblems()) {
            throw new IOException("Library loading failed in strict mode: " + report.problems().size() + " problem(s)");
        }
        return report;
    }

    /**
     * Drops method bodies for input classes that will be copied byte-for-byte. Their structural
     * signatures remain available for hierarchy and override analysis, while large shaded JARs
     * no longer retain millions of dependency instructions in memory during transformation.
     */
    public int compactTransformationExcludedClasses(ClassPool pool) {
        int compacted = 0;
        for (String className : pool.getTransformationExclusions().keySet()) {
            cancellation.throwIfCancelled();
            ClassNode compact = pool.getLibraryClasses().get(className);
            if (compact == null) {
                byte[] original = originalClassBytes.get(className);
                if (original == null) continue;
                try {
                    compact = readLibraryClass(original);
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
            pool.getClassMap().put(className, compact);
            compacted++;
        }
        return compacted;
    }

    public void loadLibraries(ClassPool pool, Path libsPath) throws IOException {
        loadLibraries(pool, new LibraryOptions(List.of(libsPath), false, false, false));
    }

    private void scanLibraryDirectory(ClassPool pool, Path directory, boolean recursive, LibraryLoadReport report) {
        try (var stream = recursive ? Files.walk(directory) : Files.list(directory)) {
            for (Path archive : stream
                    .filter(Files::isRegularFile)
                    .filter(this::isArchive)
                    .sorted()
                    .toList()) {
                cancellation.throwIfCancelled();
                loadLibraryArchive(pool, archive, report);
            }
        } catch (IOException exception) {
            report.problem(directory, "failed to scan library directory", exception);
        }
    }

    private void loadLibraryArchive(ClassPool pool, Path jarPath, LibraryLoadReport report) {
        report.archive(jarPath);
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                cancellation.throwIfCancelled();
                JarEntry entry = entries.nextElement();
                if (isClassEntry(entry.getName())) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        byte[] data = is.readAllBytes();
                        addLibraryClass(pool, data, false, report);
                    } catch (Exception exception) {
                        report.problem(jarPath, "failed to read class entry " + entry.getName(), exception);
                    }
                }
            }
        } catch (IOException exception) {
            report.problem(jarPath, "failed to load library archive", exception);
        }
    }

    private void loadRuntimeClasses(ClassPool pool, LibraryLoadReport report) {
        Map<String, ClassNode> cached = runtimeClassCache;
        if (cached == null) {
            synchronized (RUNTIME_CACHE_LOCK) {
                cached = runtimeClassCache;
                if (cached == null) {
                    cached = scanRuntimeClasses(report);
                    runtimeClassCache = cached;
                }
            }
        }
        for (Map.Entry<String, ClassNode> entry : cached.entrySet()) {
            cancellation.throwIfCancelled();
            if (pool.contains(entry.getKey())) {
                report.appShadowedClass();
            } else if (pool.getLibraryClasses().containsKey(entry.getKey())) {
                report.duplicateClass();
            } else {
                pool.addLibraryClass(entry.getKey(), entry.getValue());
                report.loadedClass(true);
            }
        }
    }

    private Map<String, ClassNode> scanRuntimeClasses(LibraryLoadReport report) {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modules = jrt.getPath("/modules");
            try (var stream = Files.walk(modules)) {
                Iterator<Path> paths = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> isClassEntry(path.toString()))
                        .iterator();
                while (paths.hasNext()) {
                    cancellation.throwIfCancelled();
                    Path classFile = paths.next();
                    try {
                        ClassNode classNode = readLibraryClass(Files.readAllBytes(classFile));
                        classes.putIfAbsent(classNode.name, classNode);
                    } catch (Exception exception) {
                        report.problem(classFile, "failed to read runtime class", exception);
                    }
                }
            }
        } catch (java.util.concurrent.CancellationException cancellationException) {
            throw cancellationException;
        } catch (Exception exception) {
            Path javaHome = Paths.get(System.getProperty("java.home", ""));
            report.problem(javaHome, "failed to scan Java runtime modules", exception);
        }
        return Collections.unmodifiableMap(classes);
    }

    private void addLibraryClass(ClassPool pool, byte[] data, boolean runtime, LibraryLoadReport report) {
        ClassNode classNode = readLibraryClass(data);
        if (pool.contains(classNode.name)) {
            report.appShadowedClass();
            if (!runtime && pool.excludeFromTransformation(classNode.name, "supplied library archive")) {
                report.excludedInputClass();
            }
            pool.getLibraryClasses().putIfAbsent(classNode.name, classNode);
            return;
        }
        if (pool.getLibraryClasses().containsKey(classNode.name)) {
            report.duplicateClass();
            return;
        }
        pool.addLibraryClass(classNode.name, classNode);
        report.loadedClass(runtime);
    }

    private ClassNode readLibraryClass(byte[] data) {
        try {
            ClassFileVersion.requireSupported(data, "Library class");
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        ClassReader reader = new ClassReader(data);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return classNode;
    }

    private boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private boolean isClassEntry(String name) {
        return name.endsWith(".class")
                && !name.endsWith("module-info.class")
                && !name.endsWith("package-info.class");
    }

    @SuppressWarnings("unchecked")
    private void detectMainClass() {
        for (String descriptor : List.of("plugin.yml", "paper-plugin.yml", "bungee.yml")) {
            byte[] yamlBytes = resources.get(descriptor);
            if (yamlBytes == null) continue;
            try {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(new String(yamlBytes, StandardCharsets.UTF_8));
                if (data != null) {
                    for (String key : List.of("main", "bootstrapper", "loader")) {
                        Object value = data.get(key);
                        if (value != null && !value.toString().isBlank()) {
                            detectedDescriptorEntrypoints.add(value.toString().trim());
                            if (detectedMainClass == null && key.equals("main")) {
                                detectedMainClass = value.toString().trim();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Logger.warn("Failed to parse {} for entrypoint detection", descriptor);
            }
        }

        detectJsonEntrypoint("velocity-plugin.json", "main");

        detectFabricMod();
    }

    private void detectJsonEntrypoint(String resource, String key) {
        byte[] bytes = resources.get(resource);
        if (bytes == null) return;
        try {
            JsonObject object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                String value = object.get(key).getAsString().trim();
                if (!value.isBlank()) {
                    detectedDescriptorEntrypoints.add(value);
                    if (detectedMainClass == null) detectedMainClass = value;
                }
            }
        } catch (RuntimeException exception) {
            Logger.warn("Failed to parse {} for entrypoint detection", resource);
        }
    }

    private void detectFabricMod() {
        byte[] fabricJson = resources.get("fabric.mod.json");
        if (fabricJson == null) return;
        isFabricMod = true;
        try {
            JsonObject obj = JsonParser.parseString(new String(fabricJson, StandardCharsets.UTF_8)).getAsJsonObject();
            if (obj.has("entrypoints") && obj.get("entrypoints").isJsonObject()) {
                JsonObject entrypointsObj = obj.getAsJsonObject("entrypoints");
                for (Map.Entry<String, JsonElement> entry : entrypointsObj.entrySet()) {
                    extractClassesFromJsonElement(entry.getValue(), detectedFabricEntrypoints);
                }
            }
            if (obj.has("mixins") && obj.get("mixins").isJsonArray()) {
                for (JsonElement elem : obj.getAsJsonArray("mixins")) {
                    if (elem.isJsonPrimitive()) {
                        detectedFabricMixins.add(elem.getAsString());
                    } else if (elem.isJsonObject() && elem.getAsJsonObject().has("config")) {
                        detectedFabricMixins.add(elem.getAsJsonObject().get("config").getAsString());
                    }
                }
            }
            if (detectedMainClass == null && !detectedFabricEntrypoints.isEmpty()) {
                detectedMainClass = detectedFabricEntrypoints.iterator().next();
            }
            Logger.info("Detected Fabric mod with {} entrypoint class(es)", detectedFabricEntrypoints.size());
        } catch (Exception e) {
            Logger.warn("Failed to parse fabric.mod.json: {}", e.getMessage());
        }
    }

    private void extractClassesFromJsonElement(JsonElement element, Set<String> target) {
        if (element == null) return;
        if (element.isJsonPrimitive()) {
            String str = element.getAsString();
            String className = extractClassNameFromEntrypointString(str);
            if (className != null && !className.isBlank()) {
                target.add(className);
            }
        } else if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("value")) {
                extractClassesFromJsonElement(obj.get("value"), target);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                extractClassesFromJsonElement(child, target);
            }
        }
    }

    private String extractClassNameFromEntrypointString(String entry) {
        if (entry == null || entry.isBlank()) return null;
        int idx = entry.indexOf("::");
        if (idx != -1) {
            return entry.substring(0, idx).trim();
        }
        return entry.trim();
    }

    public void updateFabricModJson(MappingCollector mappings) {
        if (mappings == null) return;
        byte[] fabricData = resources.get("fabric.mod.json");
        if (fabricData != null) {
            try {
                String content = new String(fabricData, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();

                if (root.has("entrypoints") && root.get("entrypoints").isJsonObject()) {
                    JsonObject entrypointsObj = root.getAsJsonObject("entrypoints");
                    for (Map.Entry<String, JsonElement> category : entrypointsObj.entrySet()) {
                        remapEntrypointElement(category.getValue(), mappings);
                    }
                }

                if (root.has("languageAdapters") && root.get("languageAdapters").isJsonObject()) {
                    JsonObject adapters = root.getAsJsonObject("languageAdapters");
                    for (Map.Entry<String, JsonElement> adapter : adapters.entrySet()) {
                        if (adapter.getValue().isJsonPrimitive()) {
                            String oldClass = adapter.getValue().getAsString();
                            String newClass = remapClassNameDot(oldClass, mappings);
                            if (!oldClass.equals(newClass)) {
                                adapters.addProperty(adapter.getKey(), newClass);
                            }
                        }
                    }
                }

                Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                String updatedJson = gson.toJson(root);
                resources.put("fabric.mod.json", updatedJson.getBytes(StandardCharsets.UTF_8));
                Logger.info("Updated fabric.mod.json with remapped class names");
            } catch (Exception e) {
                Logger.warn("Failed to update fabric.mod.json: {}", e.getMessage());
            }
        }

        updateFabricMixinConfigs(mappings);
    }

    private void remapEntrypointElement(JsonElement element, MappingCollector mappings) {
        if (element == null) return;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement child = array.get(i);
                if (child.isJsonPrimitive()) {
                    String str = child.getAsString();
                    String remapped = remapEntrypointString(str, mappings);
                    if (!str.equals(remapped)) {
                        array.set(i, new JsonPrimitive(remapped));
                    }
                } else if (child.isJsonObject()) {
                    remapEntrypointJsonObject(child.getAsJsonObject(), mappings);
                }
            }
        } else if (element.isJsonPrimitive()) {
            // string entrypoint directly under category key
        } else if (element.isJsonObject()) {
            remapEntrypointJsonObject(element.getAsJsonObject(), mappings);
        }
    }

    private void remapEntrypointJsonObject(JsonObject obj, MappingCollector mappings) {
        if (obj.has("value") && obj.get("value").isJsonPrimitive()) {
            String str = obj.get("value").getAsString();
            String remapped = remapEntrypointString(str, mappings);
            if (!str.equals(remapped)) {
                obj.addProperty("value", remapped);
            }
        }
        if (obj.has("adapter") && obj.get("adapter").isJsonPrimitive()) {
            String str = obj.get("adapter").getAsString();
            String remapped = remapClassNameDot(str, mappings);
            if (!str.equals(remapped)) {
                obj.addProperty("adapter", remapped);
            }
        }
    }

    private String remapEntrypointString(String entrypoint, MappingCollector mappings) {
        if (entrypoint == null || entrypoint.isBlank()) return entrypoint;
        int idx = entrypoint.indexOf("::");
        if (idx != -1) {
            String className = entrypoint.substring(0, idx);
            String methodPart = entrypoint.substring(idx);
            String remappedClass = remapClassNameDot(className, mappings);
            return remappedClass + methodPart;
        } else {
            return remapClassNameDot(entrypoint, mappings);
        }
    }

    private String remapClassNameDot(String classNameDot, MappingCollector mappings) {
        String internalName = classNameDot.replace('.', '/');
        String mappedInternal = mappings.getMappedClass(internalName);
        return mappedInternal.replace('/', '.');
    }

    private void updateFabricMixinConfigs(MappingCollector mappings) {
        Set<String> mixinConfigFiles = new LinkedHashSet<>(detectedFabricMixins);
        for (String resourceName : resources.keySet()) {
            if (resourceName.endsWith(".mixins.json") || resourceName.endsWith(".mixin.json")) {
                mixinConfigFiles.add(resourceName);
            }
        }

        for (String fileName : mixinConfigFiles) {
            byte[] data = resources.get(fileName);
            if (data == null) continue;
            try {
                String jsonContent = new String(data, StandardCharsets.UTF_8);
                JsonObject mixinRoot = JsonParser.parseString(jsonContent).getAsJsonObject();
                boolean modified = false;

                String pkg = mixinRoot.has("package") ? mixinRoot.get("package").getAsString() : "";

                modified |= remapMixinClassArray(mixinRoot, "mixins", pkg, mappings);
                modified |= remapMixinClassArray(mixinRoot, "client", pkg, mappings);
                modified |= remapMixinClassArray(mixinRoot, "server", pkg, mappings);

                if (modified) {
                    Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                    resources.put(fileName, gson.toJson(mixinRoot).getBytes(StandardCharsets.UTF_8));
                    Logger.info("Updated mixin config {}", fileName);
                }
            } catch (Exception e) {
                Logger.warn("Failed to parse mixin config {}: {}", fileName, e.getMessage());
            }
        }
    }

    private boolean remapMixinClassArray(JsonObject root, String arrayKey, String basePackage, MappingCollector mappings) {
        if (!root.has(arrayKey) || !root.get(arrayKey).isJsonArray()) return false;
        JsonArray array = root.getAsJsonArray(arrayKey);
        boolean modified = false;
        for (int i = 0; i < array.size(); i++) {
            JsonElement elem = array.get(i);
            if (elem.isJsonPrimitive()) {
                String relName = elem.getAsString();
                String fullClassDot = basePackage.isEmpty() ? relName : (basePackage + "." + relName);
                String mappedClassDot = remapClassNameDot(fullClassDot, mappings);
                if (!fullClassDot.equals(mappedClassDot)) {
                    String newRelName;
                    if (!basePackage.isEmpty() && mappedClassDot.startsWith(basePackage + ".")) {
                        newRelName = mappedClassDot.substring(basePackage.length() + 1);
                    } else {
                        newRelName = mappedClassDot;
                    }
                    array.set(i, new JsonPrimitive(newRelName));
                    modified = true;
                }
            }
        }
        return modified;
    }

    public void updatePluginMainClass(String oldMainClass, String newMainClass) {
        updateYamlMainClass("plugin.yml", oldMainClass, newMainClass);
        updateYamlMainClass("paper-plugin.yml", oldMainClass, newMainClass);
    }

    private void updateYamlMainClass(String fileName, String oldMainClass, String newMainClass) {
        byte[] yamlData = resources.get(fileName);
        if (yamlData == null) return;

        try {
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setProcessComments(true);
            DumperOptions dumperOptions = new DumperOptions();
            dumperOptions.setProcessComments(true);
            dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            dumperOptions.setPrettyFlow(true);
            dumperOptions.setIndent(2);
            NumberAnchorGenerator generatedAnchors = new NumberAnchorGenerator(0);
            dumperOptions.setAnchorGenerator(node -> node.getAnchor() != null
                    ? node.getAnchor()
                    : generatedAnchors.nextAnchor(node));
            Yaml yaml = new Yaml(
                    new SafeConstructor(loaderOptions),
                    new Representer(dumperOptions),
                    dumperOptions,
                    loaderOptions);
            Node document = yaml.compose(new StringReader(new String(yamlData, StandardCharsets.UTF_8)));
            if (!(document instanceof MappingNode root)) {
                Logger.warn("Cannot update main class in {}: YAML root is not a mapping", fileName);
                return;
            }

            Node updatedDocument = document;
            boolean updated = false;
            for (NodeTuple entry : root.getValue()) {
                if (!(entry.getKeyNode() instanceof ScalarNode key)
                        || !"main".equals(key.getValue())
                        || !(entry.getValueNode() instanceof ScalarNode value)) continue;
                if (!oldMainClass.equals(value.getValue())) return;

                ScalarNode replacement = new ScalarNode(value.getTag(), newMainClass,
                        value.getStartMark(), value.getEndMark(), value.getScalarStyle());
                replacement.setAnchor(value.getAnchor());
                replacement.setBlockComments(value.getBlockComments());
                replacement.setInLineComments(value.getInLineComments());
                replacement.setEndComments(value.getEndComments());
                updatedDocument = replaceYamlNode(
                        document, value, replacement, new IdentityHashMap<>());
                updated = true;
                break;
            }
            if (!updated) return;

            StringWriter output = new StringWriter();
            yaml.serialize(updatedDocument, output);
            resources.put(fileName, output.toString().getBytes(StandardCharsets.UTF_8));
            Logger.info("Updated main class in {} : {} -> {}", fileName, oldMainClass, newMainClass);
        } catch (Exception exception) {
            Logger.warn("Failed to update main class in {}: {}", fileName, exception.getMessage());
        }
    }

    private Node replaceYamlNode(
            Node node, Node target, Node replacement, IdentityHashMap<Node, Node> visited) {
        if (node == target) return replacement;
        Node previous = visited.get(node);
        if (previous != null) return previous;
        visited.put(node, node);

        if (node instanceof AnchorNode anchor) {
            Node realNode = replaceYamlNode(anchor.getRealNode(), target, replacement, visited);
            if (realNode == anchor.getRealNode()) return node;
            AnchorNode updated = new AnchorNode(realNode);
            visited.put(node, updated);
            return updated;
        }
        if (node instanceof MappingNode mapping) {
            List<NodeTuple> entries = new ArrayList<>(mapping.getValue().size());
            for (NodeTuple entry : mapping.getValue()) {
                entries.add(new NodeTuple(
                        replaceYamlNode(entry.getKeyNode(), target, replacement, visited),
                        replaceYamlNode(entry.getValueNode(), target, replacement, visited)));
            }
            mapping.setValue(entries);
        } else if (node instanceof SequenceNode sequence) {
            List<Node> values = sequence.getValue();
            for (int index = 0; index < values.size(); index++) {
                values.set(index, replaceYamlNode(values.get(index), target, replacement, visited));
            }
        }
        return node;
    }

    public String getDetectedMainClass() {
        return detectedMainClass;
    }

    @SuppressWarnings("unchecked")
    public String getCurrentPluginMainClass() {
        byte[] pluginYml = resources.get("plugin.yml");
        if (pluginYml == null) {
            pluginYml = resources.get("paper-plugin.yml");
        }
        if (pluginYml == null) {
            return null;
        }
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(new String(pluginYml, StandardCharsets.UTF_8));
            if (data != null && data.containsKey("main")) {
                return data.get("main").toString();
            }
        } catch (Exception e) {
            Logger.warn("Failed to parse current plugin main class");
        }
        return null;
    }

    public String getManifestMainClass() {
        if (manifest == null) return null;
        return manifest.getMainAttributes().getValue("Main-Class");
    }

    public void updateManifestMainClass(String oldMainClass, String newMainClass) {
        if (manifest == null) return;
        String current = manifest.getMainAttributes().getValue("Main-Class");
        if (oldMainClass.equals(current)) {
            manifest.getMainAttributes().putValue("Main-Class", newMainClass);
            Logger.info("Updated Main-Class in manifest: {} -> {}", oldMainClass, newMainClass);
        }
    }

    /**
     * Snapshot all classes BEFORE flow transforms run.
     * These snapshots are used as fallback when COMPUTE_FRAMES produces
     * invalid frames due to structurally complex bytecode from flow transforms.
     */
    public void snapshotPreFlowClasses(ClassPool pool) {
        for (ClassNode classNode : pool.getClasses()) {
            cancellation.throwIfCancelled();
            try {
                ClassWriter w = new HierarchyClassWriter(pool, ClassWriter.COMPUTE_FRAMES);
                classNode.accept(w);
                preFlowClassBytes.put(classNode.name, w.toByteArray());
            } catch (Exception e) {
                // Pre-flow snapshot failed; no fallback for this class.
            }
        }
        Logger.info("Saved pre-flow snapshots for {} classes", preFlowClassBytes.size());
    }

    /**
     * Returns a defensive copy of the class bytes captured before flow transforms ran.
     */
    public byte[] getPreFlowClassBytes(String className) {
        byte[] bytes = preFlowClassBytes.get(className);
        return bytes == null ? null : bytes.clone();
    }

    public void writeJar(ClassPool pool, Path outputPath) throws IOException {
        Path target = outputPath.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Output path has no parent: " + target);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
        boolean committed = false;

        try {
            try (JarOutputStream jos = manifest != null
                    ? new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)), manifest)
                    : new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {

            Map<String, byte[]> writtenClassBytes = runtimeChecksumClasses.isEmpty()
                    ? Map.of()
                    : new LinkedHashMap<>();

            for (Map.Entry<String, ClassNode> entry : pool.getClassMap().entrySet()) {
                cancellation.throwIfCancelled();
                ClassNode classNode = entry.getValue();
                if (isNestedClass(classNode.name)) {
                    continue;
                }
                byte[] bytes;
                String originalName = pool.getOriginalName(classNode.name);

                if (!pool.isDirty(classNode.name) && originalName.equals(classNode.name)) {
                    bytes = originalClassBytes.get(originalName);
                    if (bytes != null) {
                        JarEntry jarEntry = new JarEntry(classNode.name + ".class");
                        jos.putNextEntry(jarEntry);
                        jos.write(bytes);
                        jos.closeEntry();
                        if (runtimeChecksumClasses.contains(classNode.name)) {
                            writtenClassBytes.put(classNode.name, bytes);
                        }
                        continue;
                    }
                }

                boolean computeFrames = pool.requiresFrameComputation(classNode.name);
                if (computeFrames) removeStaleFrames(classNode);

                try {
                    int writerFlags = computeFrames ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
                    ClassWriter writer = new HierarchyClassWriter(pool, writerFlags);
                    classNode.accept(writer);
                    bytes = writer.toByteArray();

                    // Verify the generated bytes. COMPUTE_FRAMES can silently
                    // produce wrong frames when bytecode has structural issues
                    // from flow transforms (different stack sizes at merge points).
                    try {
                        ClassReader verifyReader = new ClassReader(bytes);
                        ClassNode verifyNode = new ClassNode();
                        verifyReader.accept(verifyNode, ClassReader.EXPAND_FRAMES);
                        for (MethodNode mn : verifyNode.methods) {
                            if (mn.instructions == null || mn.instructions.size() == 0) continue;
                            if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                            long frameWidth = Math.max(1, mn.maxLocals) + (long) Math.max(1, mn.maxStack);
                            long frameCells = mn.instructions.size() * frameWidth;
                            if (frameCells > MAX_POST_WRITE_VERIFIER_FRAME_CELLS) {
                                throw new IllegalStateException("verification budget exceeded by "
                                        + mn.name + mn.desc + " (" + mn.instructions.size()
                                        + " instructions, " + frameWidth + " frame slots)");
                            }
                            org.objectweb.asm.tree.analysis.Analyzer<org.objectweb.asm.tree.analysis.BasicValue> analyzer =
                                    new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier());
                            analyzer.analyze(verifyNode.name, mn);
                        }
                    } catch (Exception verifyEx) {
                        Logger.warn("Post-write verification failed for {}: {}; using pre-flow snapshot",
                                classNode.name, verifyEx.getMessage());
                        byte[] snapshot = preFlowClassBytes.get(classNode.name);
                        if (snapshot != null) {
                            bytes = snapshot;
                        }
                        // else keep the COMPUTE_FRAMES bytes as best-effort
                    }
                } catch (org.objectweb.asm.MethodTooLargeException e) {
                    Logger.warn("Method too large in {}, keeping original class: {}",
                            classNode.name, e.getMessage());
                    bytes = preFlowClassBytes.getOrDefault(classNode.name,
                            originalClassBytes.get(originalName));
                } catch (Exception e) {
                    Logger.warn("Class writing failed for {}: {}; using pre-flow snapshot",
                            classNode.name, e.getMessage());
                    bytes = preFlowClassBytes.getOrDefault(classNode.name,
                            originalClassBytes.get(originalName));
                }

                // Final failsafe
                if (bytes == null) {
                    Logger.error("No valid bytes for {}. Skipping.", classNode.name);
                    continue;
                }

                JarEntry jarEntry = new JarEntry(classNode.name + ".class");
                jos.putNextEntry(jarEntry);
                jos.write(bytes);
                jos.closeEntry();
                if (runtimeChecksumClasses.contains(classNode.name)) {
                    writtenClassBytes.put(classNode.name, bytes);
                }
            }

            if (!writtenClassBytes.isEmpty()) {
                resources.put("META-INF/frostfuscator/runtime-checksums.tsv",
                        buildRuntimeChecksumIndex(writtenClassBytes));
            }

            if (!nestedJars.isEmpty()) {
                repackNestedJars(pool);
            }

            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                cancellation.throwIfCancelled();
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jos.putNextEntry(jarEntry);
                jos.write(entry.getValue());
                jos.closeEntry();
            }
            }

            cancellation.throwIfCancelled();
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            committed = true;
        } finally {
            if (!committed) Files.deleteIfExists(temporary);
        }

        Logger.info("Written protected jar to {}", target);
    }

    private byte[] buildRuntimeChecksumIndex(Map<String, byte[]> classBytes) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder index = new StringBuilder();
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(classBytes).entrySet()) {
                index.append(entry.getKey())
                        .append('\t')
                        .append(java.util.HexFormat.of().formatHex(digest.digest(entry.getValue())))
                        .append('\n');
            }
            return index.toString().getBytes(StandardCharsets.UTF_8);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static void removeStaleFrames(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (method.instructions == null) continue;
            AbstractInsnNode instruction = method.instructions.getFirst();
            while (instruction != null) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof FrameNode) method.instructions.remove(instruction);
                instruction = next;
            }
        }
    }

    private String commonPoolType(ClassPool pool, String type1, String type2) {
        if (isAssignableFrom(pool, type2, type1)) return type1;
        if (isAssignableFrom(pool, type1, type2)) return type2;

        ClassNode first = pool.getClass(type1);
        if (first == null) return null;
        String current = first.superName;
        while (current != null) {
            if (isAssignableFrom(pool, type2, current)) return current;
            ClassNode node = pool.getClass(current);
            current = node == null ? null : node.superName;
        }
        return null;
    }

    private boolean isAssignableFrom(ClassPool pool, String child, String parent) {
        if (child.equals(parent)) return true;
        if (parent.equals("java/lang/Object")) return true;

        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(child);
        while (!pending.isEmpty() && visited.size() < 256) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current.equals(parent)) return true;
            ClassNode node = pool.getClass(current);
            if (node == null) continue;
            if (node.superName != null) pending.addLast(node.superName);
            if (node.interfaces != null) pending.addAll(node.interfaces);
        }
        return false;
    }

    private final class HierarchyClassWriter extends ClassWriter {
        private final ClassPool pool;

        private HierarchyClassWriter(ClassPool pool, int flags) {
            super(flags);
            this.pool = pool;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            if (type1.startsWith("[") || type2.startsWith("[")) {
                return commonArrayType(type1, type2);
            }

            String pooled = commonPoolType(pool, type1, type2);
            if (pooled != null) return pooled;
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (RuntimeException ignored) {
                return "java/lang/Object";
            }
        }

        private String commonArrayType(String type1, String type2) {
            if (!type1.startsWith("[") || !type2.startsWith("[")) {
                String nonArray = type1.startsWith("[") ? type2 : type1;
                return nonArray.equals("java/lang/Cloneable") || nonArray.equals("java/io/Serializable")
                        ? nonArray
                        : "java/lang/Object";
            }

            String component1 = type1.substring(1);
            String component2 = type2.substring(1);
            if (component1.equals(component2)) return type1;
            if (isReferenceDescriptor(component1) && isReferenceDescriptor(component2)) {
                String common = getCommonSuperClass(toFrameType(component1), toFrameType(component2));
                return common.startsWith("[") ? "[" + common : "[L" + common + ";";
            }
            return "java/lang/Object";
        }

        private boolean isReferenceDescriptor(String descriptor) {
            return descriptor.charAt(0) == '[' || descriptor.charAt(0) == 'L';
        }

        private String toFrameType(String descriptor) {
            return descriptor.charAt(0) == '['
                    ? descriptor
                    : Type.getType(descriptor).getInternalName();
        }
    }
}
