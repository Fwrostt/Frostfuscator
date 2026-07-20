package dev.frost.obfuscator.gui.analysis;

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

        int classes = 0;
        int resources = 0;
        int maxClassMajor = 0;
        int complexity = 0;
        boolean reflection = false;
        boolean services = false;
        boolean natives = false;
        boolean signed = false;
        boolean fatJar = false;
        Set<String> roots = new TreeSet<>();
        Set<String> frameworks = new LinkedHashSet<>();
        List<String> nestedJars = new ArrayList<>();
        List<String> serviceEntries = new ArrayList<>();
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
                if (mainClass.isBlank()) {
                    mainClass = manifestData.getOrDefault("Start-Class", "");
                }
                classPath = value(manifest.getMainAttributes(), Attributes.Name.CLASS_PATH);
            }

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String upper = name.toUpperCase(Locale.ROOT);
                if (name.endsWith(".class")) {
                    classes++;
                    byte[] bytes;
                    try (var stream = jar.getInputStream(entry)) {
                        bytes = stream.readAllBytes();
                    }
                    if (bytes.length >= 8) {
                        maxClassMajor = Math.max(maxClassMajor, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
                    }
                    String constants = new String(bytes, StandardCharsets.ISO_8859_1);
                    reflection |= constants.contains("java/lang/reflect")
                            || constants.contains("java/lang/Class") && constants.contains("forName")
                            || constants.contains("java/lang/invoke/MethodHandles");
                    complexity += count(bytes, (byte) 0x99) + count(bytes, (byte) 0xA7)
                            + count(bytes, (byte) 0xAA) * 3 + count(bytes, (byte) 0xAB) * 3;
                    String logical = stripClassPrefix(name);
                    int slash = logical.indexOf('/');
                    if (slash > 0) roots.add(logical.substring(0, slash).replace('/', '.'));
                } else {
                    resources++;
                }

                if (name.startsWith("META-INF/services/")) {
                    services = true;
                    serviceEntries.add(name.substring("META-INF/services/".length()));
                }
                natives |= name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")
                        || name.endsWith(".jnilib");
                signed |= upper.startsWith("META-INF/")
                        && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
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
        List<String> exclusions = new ArrayList<>();
        if (!mainClass.isBlank()) keepRules.add(mainClass);
        keepRules.addAll(serviceEntries);
        if (reflection) {
            exclusions.add(".*(?:Dto|DTO|Entity|Model|Config|Factory|Provider).*");
        }
        if (frameworks.contains("Spring") || frameworks.contains("Spring Boot")) {
            exclusions.add(".*(?:Controller|Service|Repository|Configuration|Component).*");
        }

        String base = removeExtension(jarPath.getFileName().toString());
        Path output = jarPath.resolveSibling(base + "-protected.jar");
        String suggestedPackage = roots.isEmpty() ? "obf" : roots.iterator().next() + ".internal";
        String dictionary = classes > 5000 ? "numeric" : "alphabet";
        int javaVersion = maxClassMajor == 0 ? 0 : Math.max(1, maxClassMajor - 44);
        int complexityScore = classes == 0 ? 0 : Math.min(100, complexity / Math.max(1, classes * 3));

        return new ProjectAnalysis(jarPath, Files.size(jarPath), classes, resources, complexityScore,
                javaVersion, mainClass, List.copyOf(roots), List.copyOf(frameworks), fatJar,
                Map.copyOf(manifestData), reflection, services, natives, signed, List.copyOf(declared),
                resolution.resolved(), resolution.unresolved(), List.copyOf(keepRules), List.copyOf(exclusions),
                output.toString(), suggestedPackage, dictionary);
    }

    private Resolution resolveLibraries(Path jar, List<String> declared) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        Path parent = jar.getParent();
        for (String dependency : declared) {
            if (dependency.contains("!/") || dependency.startsWith("BOOT-INF/") || dependency.startsWith("WEB-INF/")) {
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
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path jmods = javaHome.resolve("jmods");
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
        for (Path root : List.of(home.resolve(".m2/repository"), home.resolve(".gradle/caches/modules-2/files-2.1"))) {
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

    private static int count(byte[] bytes, byte value) {
        int count = 0;
        for (byte item : bytes) if (item == value) count++;
        return count;
    }

    private static String stripClassPrefix(String name) {
        for (String prefix : List.of("BOOT-INF/classes/", "WEB-INF/classes/")) {
            if (name.startsWith(prefix)) return name.substring(prefix.length());
        }
        return name;
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
}
