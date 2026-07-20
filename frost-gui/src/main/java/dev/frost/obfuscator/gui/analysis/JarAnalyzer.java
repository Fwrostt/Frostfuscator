package dev.frost.obfuscator.gui.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class JarAnalyzer {
    private static final Map<String, String> FRAMEWORK_MARKERS = Map.ofEntries(
            Map.entry("org/springframework/", "Spring"),
            Map.entry("BOOT-INF/", "Spring Boot"),
            Map.entry("io/quarkus/", "Quarkus"),
            Map.entry("org/hibernate/", "Hibernate"),
            Map.entry("com/google/inject/", "Guice"),
            Map.entry("net/minecraft/", "Minecraft"),
            Map.entry("org/bukkit/", "Bukkit/Spigot"),
            Map.entry("net/fabricmc/", "Fabric"),
            Map.entry("com/fasterxml/jackson/", "Jackson"),
            Map.entry("kotlin/", "Kotlin"),
            Map.entry("scala/", "Scala")
    );

    public ProjectAnalysis analyze(Path input) throws IOException {
        Path jarPath = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException("The selected input JAR does not exist: " + jarPath);
        }

        Scan scan = new Scan();
        int classEntries = 0;
        int resources = 0;
        int maxClassMajor = 0;
        boolean services = false;
        boolean natives = false;
        boolean signed = false;
        boolean fatJar = false;
        Set<String> roots = new TreeSet<>();
        Set<String> frameworks = new LinkedHashSet<>();
        List<String> nestedJars = new ArrayList<>();
        List<String> serviceEntries = new ArrayList<>();
        List<String> serviceProviders = new ArrayList<>();
        Map<String, String> manifestData = new LinkedHashMap<>();
        String mainClass = "";
        String classPath = "";

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                for (Map.Entry<Object, Object> entry : manifest.getMainAttributes().entrySet()) {
                    manifestData.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                mainClass = value(manifest.getMainAttributes(), Attributes.Name.MAIN_CLASS);
                if (mainClass.isBlank()) mainClass = manifestData.getOrDefault("Start-Class", "");
                classPath = value(manifest.getMainAttributes(), Attributes.Name.CLASS_PATH);
            }

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String upper = name.toUpperCase(Locale.ROOT);
                byte[] bytes;
                try (var stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (name.endsWith(".class")) {
                    classEntries++;
                    maxClassMajor = Math.max(maxClassMajor, classMajor(bytes));
                    if (!analyzeClass(bytes, scan, roots)) {
                        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
                        scan.reflection |= constants.contains("java/lang/reflect")
                                || constants.contains("java/lang/Class") && constants.contains("forName")
                                || constants.contains("java/lang/invoke/MethodHandles");
                    }
                } else {
                    resources++;
                    scanResource(name, bytes.length, scan);
                }

                if (name.startsWith("META-INF/services/")) {
                    services = true;
                    String service = name.substring("META-INF/services/".length()).trim();
                    if (!service.isBlank()) serviceEntries.add(service);
                    for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                        String provider = line.replaceFirst("#.*$", "").trim();
                        if (!provider.isBlank()) serviceProviders.add(provider);
                    }
                }
                natives |= name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")
                        || name.endsWith(".jnilib");
                signed |= upper.startsWith("META-INF/")
                        && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                        || upper.endsWith(".DSA") || upper.endsWith(".EC"));
                if (name.endsWith(".jar")) nestedJars.add(name);
                fatJar |= name.startsWith("BOOT-INF/classes/") || name.startsWith("BOOT-INF/lib/")
                        || name.startsWith("WEB-INF/lib/") || name.startsWith("lib/") && name.endsWith(".jar");
                for (Map.Entry<String, String> marker : FRAMEWORK_MARKERS.entrySet()) {
                    if (name.startsWith(marker.getKey())) frameworks.add(marker.getValue());
                }
            }
        }

        List<String> declared = new ArrayList<>();
        if (!classPath.isBlank()) {
            declared.addAll(Arrays.stream(classPath.trim().split("\\s+")).filter(s -> !s.isBlank()).toList());
        }
        declared.addAll(nestedJars);
        Resolution resolution = resolveLibraries(jarPath, declared);

        List<String> keepRules = new ArrayList<>();
        if (!mainClass.isBlank()) keepRules.add(exactRule(mainClass));
        serviceEntries.forEach(name -> addUnique(keepRules, exactRule(name)));
        serviceProviders.forEach(name -> addUnique(keepRules, exactRule(name)));
        scan.reflectiveClassNames.forEach(name -> addUnique(keepRules, exactRule(name)));

        List<String> exclusions = new ArrayList<>(keepRules);
        if (scan.reflection) {
            addUnique(exclusions, ".*(?:Dto|DTO|Entity|Model|Config|Factory|Provider).*");
        }
        if (frameworks.contains("Spring") || frameworks.contains("Spring Boot")) {
            addUnique(exclusions, ".*(?:Controller|Service|Repository|Configuration|Component).*");
        }
        if (frameworks.contains("Hibernate") || frameworks.contains("Jackson")) {
            addUnique(exclusions, ".*(?:Entity|Embeddable|Converter|Serializer|Deserializer|Dto|DTO|Model).*");
        }

        List<BytecodeInventory.CompatibilitySignal> signals = compatibilitySignals(
                scan, frameworks, services, natives, signed, exclusions, serviceProviders);
        BytecodeInventory inventory = scan.toInventory(signals);

        String base = removeExtension(jarPath.getFileName().toString());
        Path output = jarPath.resolveSibling(base + "-protected.jar");
        String suggestedPackage = roots.isEmpty() ? "obf" : roots.iterator().next() + ".internal";
        String dictionary = classEntries > 5000 ? "numeric" : "alphabet";
        int javaVersion = maxClassMajor == 0 ? 0 : Math.max(1, maxClassMajor - 44);
        int complexityScore = scan.methodCount == 0 ? 0
                : (int) Math.min(100, Math.round(100d * scan.complexity
                / Math.max(1d, scan.instructionCount + scan.methodCount * 4d)));

        return new ProjectAnalysis(jarPath, Files.size(jarPath), classEntries, resources,
                complexityScore, javaVersion, mainClass, List.copyOf(roots), List.copyOf(frameworks),
                fatJar, Map.copyOf(manifestData), scan.reflection, services, natives, signed,
                List.copyOf(declared), resolution.resolved(), resolution.unresolved(),
                List.copyOf(keepRules), List.copyOf(exclusions), output.toString(),
                suggestedPackage, dictionary, inventory);
    }

    private static boolean analyzeClass(byte[] bytes, Scan scan, Set<String> roots) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_FRAMES);
            String owner = node.name.replace('/', '.');
            String packageName = packageName(owner);
            if (!packageName.isBlank()) roots.add(packageName.substring(0,
                    packageName.indexOf('.') < 0 ? packageName.length() : packageName.indexOf('.')));

            boolean isInterface = flag(node.access, Opcodes.ACC_INTERFACE);
            boolean isAnnotation = flag(node.access, Opcodes.ACC_ANNOTATION);
            boolean isEnum = flag(node.access, Opcodes.ACC_ENUM);
            boolean isRecord = flag(node.access, Opcodes.ACC_RECORD) || node.recordComponents != null;
            boolean isAbstract = flag(node.access, Opcodes.ACC_ABSTRACT) && !isInterface;
            if (isInterface) scan.interfaceCount++;
            if (isAnnotation) scan.annotationClassCount++;
            if (isEnum) scan.enumCount++;
            if (isRecord) scan.recordCount++;
            if (isAbstract) scan.abstractClassCount++;
            if (flag(node.access, Opcodes.ACC_SYNTHETIC)) scan.syntheticClassCount++;
            if (node.outerClass != null || node.name.contains("$")) scan.innerClassCount++;

            int classAnnotations = annotations(node.visibleAnnotations) + annotations(node.invisibleAnnotations)
                    + annotations(node.visibleTypeAnnotations) + annotations(node.invisibleTypeAnnotations);
            scan.annotationCount += classAnnotations;
            scan.fieldCount += node.fields.size();
            for (FieldNode field : node.fields) {
                scan.annotationCount += annotations(field.visibleAnnotations) + annotations(field.invisibleAnnotations)
                        + annotations(field.visibleTypeAnnotations) + annotations(field.invisibleTypeAnnotations);
                if (field.value instanceof String text) {
                    scan.addString(text, owner + "." + field.name);
                } else if (field.value instanceof Number) {
                    scan.numericConstantCount++;
                }
            }

            int classInstructions = 0;
            long classStringsBefore = scan.stringLiteralCount;
            for (MethodNode method : node.methods) {
                classInstructions += analyzeMethod(owner, method, scan);
            }
            int classStrings = (int) (scan.stringLiteralCount - classStringsBefore);
            String kind = isAnnotation ? "Annotation" : isEnum ? "Enum" : isRecord ? "Record"
                    : isInterface ? "Interface" : isAbstract ? "Abstract class" : "Class";
            scan.classes.add(new BytecodeInventory.ClassInsight(owner, packageName, kind,
                    node.fields.size(), node.methods.size(), classInstructions, classStrings,
                    classAnnotations, classFlags(node.access)));
            return true;
        } catch (RuntimeException ignored) {
            // Preserve the rest of the archive when a single class is malformed.
            return false;
        }
    }

    private static int analyzeMethod(String owner, MethodNode method, Scan scan) {
        scan.methodCount++;
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) scan.constructorCount++;
        if (flag(method.access, Opcodes.ACC_NATIVE)) scan.nativeMethodCount++;
        if (flag(method.access, Opcodes.ACC_ABSTRACT)) scan.abstractMethodCount++;
        if (flag(method.access, Opcodes.ACC_SYNCHRONIZED)) scan.synchronizedMethodCount++;
        if (flag(method.access, Opcodes.ACC_SYNTHETIC)) scan.syntheticMethodCount++;
        if (flag(method.access, Opcodes.ACC_BRIDGE)) scan.bridgeMethodCount++;
        if (flag(method.access, Opcodes.ACC_PUBLIC)) scan.publicMethodCount++;
        if (flag(method.access, Opcodes.ACC_STATIC)) scan.staticMethodCount++;
        scan.annotationCount += annotations(method.visibleAnnotations) + annotations(method.invisibleAnnotations)
                + annotations(method.visibleTypeAnnotations) + annotations(method.invisibleTypeAnnotations);
        if (method.localVariables != null) scan.localVariableCount += method.localVariables.size();
        int tryCatch = method.tryCatchBlocks == null ? 0 : method.tryCatchBlocks.size();
        scan.tryCatchCount += tryCatch;

        int instructions = 0;
        int strings = 0;
        int calls = 0;
        int branches = 0;
        int switches = 0;
        boolean unsupportedVirtualization = false;
        String location = owner + "." + method.name + method.desc;
        if (method.instructions != null) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LineNumberNode) {
                    scan.lineNumberCount++;
                    continue;
                }
                if (insn.getOpcode() >= 0) {
                    instructions++;
                    scan.instructionCount++;
                }
                if (insn instanceof LdcInsnNode ldc) {
                    if (ldc.cst instanceof String text) {
                        strings++;
                        scan.addString(text, location);
                        if (looksLikeClassName(text)) scan.reflectiveClassNames.add(text);
                    } else if (ldc.cst instanceof Number) {
                        scan.numericConstantCount++;
                    }
                } else if (insn instanceof JumpInsnNode) {
                    branches++;
                    scan.branchCount++;
                } else if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                    switches++;
                    scan.switchCount++;
                } else if (insn instanceof MethodInsnNode call) {
                    calls++;
                    scan.methodCallCount++;
                    if (isReflectionCall(call)) {
                        scan.reflection = true;
                        scan.reflectionLocations.add(location + " -> " + call.owner.replace('/', '.')
                                + "." + call.name);
                    }
                } else if (insn instanceof FieldInsnNode) {
                    scan.fieldAccessCount++;
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    scan.invokeDynamicCount++;
                    unsupportedVirtualization = true;
                } else if (insn instanceof MultiANewArrayInsnNode) {
                    unsupportedVirtualization = true;
                }
            }
        }
        int complexity = 1 + branches + switches * 2 + tryCatch;
        scan.complexity += complexity;
        boolean concrete = !method.name.equals("<init>") && !method.name.equals("<clinit>")
                && !flag(method.access, Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)
                && instructions >= 8;
        if (concrete && !unsupportedVirtualization) scan.virtualizableMethodCount++;
        if (concrete && flag(method.access, Opcodes.ACC_STATIC)
                && !flag(method.access, Opcodes.ACC_SYNTHETIC) && tryCatch == 0) {
            scan.outlineableMethodCount++;
        }
        scan.methods.add(new BytecodeInventory.MethodInsight(owner, method.name, method.desc,
                instructions, complexity, strings, calls, tryCatch, methodFlags(method.access)));
        return instructions;
    }

    private static List<BytecodeInventory.CompatibilitySignal> compatibilitySignals(
            Scan scan, Set<String> frameworks, boolean services, boolean natives, boolean signed,
            List<String> exclusions, List<String> providers) {
        List<BytecodeInventory.CompatibilitySignal> signals = new ArrayList<>();
        if (scan.reflection) {
            signals.add(new BytecodeInventory.CompatibilitySignal("reflection", "warning",
                    "Runtime name lookup detected",
                    scan.reflectionLocations.size() + " reflective call site(s) can depend on stable names.",
                    List.of("class-rename", "method-rename", "field-rename", "dead-code-elimination"),
                    exclusions));
        }
        if (services) {
            signals.add(new BytecodeInventory.CompatibilitySignal("services", "warning",
                    "ServiceLoader providers require stable metadata",
                    providers.size() + " provider class(es) were declared in META-INF/services.",
                    List.of("class-rename", "jar-shrinker"),
                    providers.stream().map(JarAnalyzer::exactRule).toList()));
        }
        if (!frameworks.isEmpty()) {
            signals.add(new BytecodeInventory.CompatibilitySignal("frameworks", "warning",
                    "Framework-managed names and annotations detected",
                    String.join(", ", frameworks) + " may instantiate classes through metadata or reflection.",
                    List.of("class-rename", "method-rename", "field-rename", "metadata-noise",
                            "dead-code-elimination"), exclusions));
        }
        if (natives || scan.nativeMethodCount > 0) {
            signals.add(new BytecodeInventory.CompatibilitySignal("native", "warning",
                    "Native bindings require stable symbols and resources",
                    scan.nativeMethodCount + " native method(s) and embedded native resources were detected.",
                    List.of("method-rename", "classloader-encryption", "resource-encryption"),
                    List.of()));
        }
        if (signed) {
            signals.add(new BytecodeInventory.CompatibilitySignal("signed", "error",
                    "Existing archive signature will be invalidated",
                    "Bytecode or resource changes invalidate META-INF signature metadata. Re-sign the output.",
                    List.of("all mutating transformers"), List.of()));
        }
        if (scan.invokeDynamicCount > 0) {
            signals.add(new BytecodeInventory.CompatibilitySignal("invokedynamic", "info",
                    "Dynamic call sites constrain virtualization",
                    scan.invokeDynamicCount + " invokedynamic instruction(s) were found; affected methods are excluded.",
                    List.of("virtualization"), List.of()));
        }
        return List.copyOf(signals);
    }

    private static void scanResource(String name, int bytes, Scan scan) {
        String file = name.substring(name.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        String type = dot < 0 ? "(no extension)" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
        scan.resourceEntries.add(new BytecodeInventory.ResourceEntry(name, type, bytes));
        BytecodeInventory.ResourceInsight current = scan.resources.get(type);
        scan.resources.put(type, current == null
                ? new BytecodeInventory.ResourceInsight(1, bytes)
                : new BytecodeInventory.ResourceInsight(current.files() + 1, current.bytes() + bytes));
    }

    private Resolution resolveLibraries(Path jar, List<String> declared) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        Path parent = jar.getParent();
        for (String dependency : declared) {
            if (dependency.contains("!/") || dependency.startsWith("BOOT-INF/")
                    || dependency.startsWith("WEB-INF/")) {
                resolved.add("Nested: " + dependency);
                continue;
            }
            Path direct = parent.resolve(dependency).normalize();
            if (Files.exists(direct)) {
                resolved.add(direct.toString());
                continue;
            }
            String fileName = Path.of(dependency).getFileName().toString();
            Optional<Path> adjacent = findAdjacent(parent, fileName);
            if (adjacent.isPresent()) {
                resolved.add(adjacent.get().toString());
                continue;
            }
            Optional<Path> cache = findInCommonCache(fileName);
            if (cache.isPresent()) resolved.add(cache.get().toString());
            else unresolved.add(dependency);
        }
        Path jmods = Path.of(System.getProperty("java.home")).resolve("jmods");
        if (Files.isDirectory(jmods)) resolved.add("Java runtime: " + jmods);
        return new Resolution(List.copyOf(resolved), List.copyOf(unresolved));
    }

    private Optional<Path> findAdjacent(Path parent, String fileName) {
        for (String folder : List.of("lib", "libs", "library", "libraries")) {
            Path candidate = parent.resolve(folder).resolve(fileName);
            if (Files.exists(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private Optional<Path> findInCommonCache(String fileName) {
        Path home = Path.of(System.getProperty("user.home"));
        for (Path root : List.of(home.resolve(".m2/repository"),
                home.resolve(".gradle/caches/modules-2/files-2.1"))) {
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.find(root, 7, (path, attrs) -> attrs.isRegularFile()
                    && path.getFileName().toString().equals(fileName))) {
                Optional<Path> found = paths.findFirst();
                if (found.isPresent()) return found;
            } catch (IOException ignored) {
            }
        }
        return Optional.empty();
    }

    private static boolean isReflectionCall(MethodInsnNode call) {
        return call.owner.startsWith("java/lang/reflect/")
                || call.owner.equals("java/lang/Class") && Set.of("forName", "getMethod",
                "getDeclaredMethod", "getField", "getDeclaredField", "getConstructor",
                "getDeclaredConstructor").contains(call.name)
                || call.owner.startsWith("java/lang/invoke/MethodHandles")
                || call.owner.equals("java/util/ServiceLoader");
    }

    private static boolean looksLikeClassName(String value) {
        return value.length() > 3 && value.length() < 240
                && value.matches("[a-zA-Z_$][\\w$]*(?:[./][a-zA-Z_$][\\w$]*)+")
                && !value.contains("://");
    }

    private static String stringCategory(String value) {
        if (value.contains("://")) return "URL";
        if (looksLikeClassName(value)) return "Class or resource name";
        if (value.matches("(?i).*(password|secret|token|api[-_]?key|private[-_]?key).*")) return "Sensitive marker";
        if (value.matches(".*[\\\\/].*")) return "Path";
        if (value.matches("[\\w.-]+@[\\w.-]+")) return "Email";
        if (value.length() >= 80) return "Long text";
        return "Text";
    }

    private static int annotations(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static boolean flag(int access, int flags) {
        return (access & flags) != 0;
    }

    private static String classFlags(int access) {
        List<String> flags = new ArrayList<>();
        if (flag(access, Opcodes.ACC_PUBLIC)) flags.add("public");
        if (flag(access, Opcodes.ACC_FINAL)) flags.add("final");
        if (flag(access, Opcodes.ACC_SYNTHETIC)) flags.add("synthetic");
        return String.join(", ", flags);
    }

    private static String methodFlags(int access) {
        List<String> flags = new ArrayList<>();
        if (flag(access, Opcodes.ACC_PUBLIC)) flags.add("public");
        else if (flag(access, Opcodes.ACC_PROTECTED)) flags.add("protected");
        else if (flag(access, Opcodes.ACC_PRIVATE)) flags.add("private");
        if (flag(access, Opcodes.ACC_STATIC)) flags.add("static");
        if (flag(access, Opcodes.ACC_FINAL)) flags.add("final");
        if (flag(access, Opcodes.ACC_ABSTRACT)) flags.add("abstract");
        if (flag(access, Opcodes.ACC_NATIVE)) flags.add("native");
        if (flag(access, Opcodes.ACC_SYNCHRONIZED)) flags.add("synchronized");
        if (flag(access, Opcodes.ACC_SYNTHETIC)) flags.add("synthetic");
        if (flag(access, Opcodes.ACC_BRIDGE)) flags.add("bridge");
        return String.join(", ", flags);
    }

    private static int classMajor(byte[] bytes) {
        return bytes.length < 8 ? 0 : ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
    }

    private static String packageName(String owner) {
        int dot = owner.lastIndexOf('.');
        return dot < 0 ? "" : owner.substring(0, dot);
    }

    private static String exactRule(String className) {
        return "^" + className.trim().replace("/", ".").replace(".", "\\.") + "$";
    }

    private static void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) values.add(value);
    }

    private static String value(Attributes attributes, Attributes.Name name) {
        String value = attributes.getValue(name);
        return value == null ? "" : value;
    }

    private static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private record Resolution(List<String> resolved, List<String> unresolved) {}

    private static final class StringAggregate {
        private int occurrences;
        private final LinkedHashSet<String> locations = new LinkedHashSet<>();
    }

    private static final class Scan {
        private long fieldCount;
        private long methodCount;
        private long constructorCount;
        private long instructionCount;
        private long stringLiteralCount;
        private long stringCharacterCount;
        private long numericConstantCount;
        private long branchCount;
        private long switchCount;
        private long tryCatchCount;
        private long methodCallCount;
        private long fieldAccessCount;
        private long invokeDynamicCount;
        private long annotationCount;
        private long nativeMethodCount;
        private long abstractMethodCount;
        private long synchronizedMethodCount;
        private long syntheticMethodCount;
        private long bridgeMethodCount;
        private long publicMethodCount;
        private long staticMethodCount;
        private long virtualizableMethodCount;
        private long outlineableMethodCount;
        private long lineNumberCount;
        private long localVariableCount;
        private long interfaceCount;
        private long abstractClassCount;
        private long enumCount;
        private long recordCount;
        private long annotationClassCount;
        private long syntheticClassCount;
        private long innerClassCount;
        private long complexity;
        private boolean reflection;
        private final List<BytecodeInventory.ClassInsight> classes = new ArrayList<>();
        private final List<BytecodeInventory.MethodInsight> methods = new ArrayList<>();
        private final LinkedHashMap<String, StringAggregate> strings = new LinkedHashMap<>();
        private final TreeMap<String, BytecodeInventory.ResourceInsight> resources = new TreeMap<>();
        private final List<BytecodeInventory.ResourceEntry> resourceEntries = new ArrayList<>();
        private final LinkedHashSet<String> reflectiveClassNames = new LinkedHashSet<>();
        private final LinkedHashSet<String> reflectionLocations = new LinkedHashSet<>();

        private void addString(String value, String location) {
            stringLiteralCount++;
            stringCharacterCount += value.length();
            StringAggregate aggregate = strings.computeIfAbsent(value, key -> new StringAggregate());
            aggregate.occurrences++;
            aggregate.locations.add(location);
        }

        private BytecodeInventory toInventory(List<BytecodeInventory.CompatibilitySignal> signals) {
            List<BytecodeInventory.StringInsight> stringInsights = strings.entrySet().stream()
                    .map(entry -> new BytecodeInventory.StringInsight(entry.getKey(),
                            entry.getValue().occurrences, entry.getKey().length(),
                            List.copyOf(entry.getValue().locations), stringCategory(entry.getKey())))
                    .sorted(Comparator.comparingInt(BytecodeInventory.StringInsight::occurrences).reversed()
                            .thenComparingInt(BytecodeInventory.StringInsight::characters).reversed())
                    .toList();
            methods.sort(Comparator.comparing(BytecodeInventory.MethodInsight::owner)
                    .thenComparing(BytecodeInventory.MethodInsight::name)
                    .thenComparing(BytecodeInventory.MethodInsight::descriptor));
            classes.sort(Comparator.comparing(BytecodeInventory.ClassInsight::name));
            resourceEntries.sort(Comparator.comparing(BytecodeInventory.ResourceEntry::name));
            return new BytecodeInventory(fieldCount, methodCount, constructorCount, instructionCount,
                    stringLiteralCount, strings.size(), stringCharacterCount, numericConstantCount,
                    branchCount, switchCount, tryCatchCount, methodCallCount, fieldAccessCount,
                    invokeDynamicCount, annotationCount, nativeMethodCount, abstractMethodCount,
                    synchronizedMethodCount, syntheticMethodCount, bridgeMethodCount, publicMethodCount,
                    staticMethodCount, virtualizableMethodCount, outlineableMethodCount, lineNumberCount,
                    localVariableCount, interfaceCount, abstractClassCount, enumCount, recordCount,
                    annotationClassCount, syntheticClassCount, innerClassCount, List.copyOf(classes),
                    List.copyOf(methods), stringInsights, List.copyOf(resourceEntries),
                    Map.copyOf(resources), signals);
        }
    }
}
