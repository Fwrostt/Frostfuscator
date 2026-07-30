package dev.frost.graph.bytecode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Loads application and optional library archives without retaining open files or ASM nodes. */
public final class JarGraphProjectLoader {
    public BytecodeProject load(Path input, Collection<Path> libraries) throws IOException {
        Objects.requireNonNull(input, "input");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Set<String> libraryClasses = new LinkedHashSet<>();
        loadArchive(input, classes, null);
        if (libraries != null) {
            for (Path library : libraries) {
                if (library == null || !Files.exists(library)) continue;
                if (Files.isDirectory(library)) {
                    try (var stream = Files.walk(library)) {
                        for (Path archive : stream.filter(Files::isRegularFile)
                                .filter(JarGraphProjectLoader::isArchive).sorted().toList()) {
                            loadArchive(archive, classes, libraryClasses);
                        }
                    }
                } else if (isArchive(library)) {
                    loadArchive(library, classes, libraryClasses);
                }
            }
        }
        return new BytecodeProject(classes, libraryClasses);
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".jmod");
    }

    private static void loadArchive(Path path, Map<String, byte[]> classes, Set<String> libraries) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entry.isDirectory() || !entryName.endsWith(".class") || entryName.equals("module-info.class")) continue;
                String name = className(entryName);
                if (name == null) continue;
                // Application classes win over equally named library classes.
                if (libraries != null && classes.containsKey(name)) continue;
                try (var in = jar.getInputStream(entry)) {
                    classes.put(name, in.readAllBytes());
                    if (libraries != null) libraries.add(name);
                }
            }
        }
    }

    private static String className(String entryName) {
        if (entryName.startsWith("META-INF/versions/")) return null;
        String name = entryName.substring(0, entryName.length() - 6);
        for (String prefix : List.of("BOOT-INF/classes/", "WEB-INF/classes/", "classes/")) {
            if (name.startsWith(prefix)) return name.substring(prefix.length());
        }
        return name;
    }
}
