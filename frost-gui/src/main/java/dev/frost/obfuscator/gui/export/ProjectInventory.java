package dev.frost.obfuscator.gui.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Inspector and inventory generator for project archives.
 * Exports detailed structural breakdown as JSON (reports/inventory.json).
 */
public record ProjectInventory(
        String archiveName,
        long totalSize,
        int totalEntries,
        int classCount,
        int resourceCount,
        Map<String, String> manifestAttributes,
        List<String> serviceProviders,
        List<String> embeddedJars,
        List<String> nativeLibraries,
        List<String> configurationFiles,
        List<String> certificatesAndSignatures,
        Map<Integer, Integer> classVersionDistribution
) {
    public static ProjectInventory scan(Path archive) throws IOException {
        Path normalized = archive.toAbsolutePath().normalize();
        Map<String, String> manifestAttrs = new LinkedHashMap<>();
        List<String> services = new ArrayList<>();
        List<String> jars = new ArrayList<>();
        List<String> natives = new ArrayList<>();
        List<String> configs = new ArrayList<>();
        List<String> certs = new ArrayList<>();
        Map<Integer, Integer> versionDist = new TreeMap<>();

        int totalEntries = 0;
        int classCount = 0;
        int resourceCount = 0;

        try (JarFile jar = new JarFile(normalized.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                manifest.getMainAttributes().forEach((key, val) ->
                        manifestAttrs.put(String.valueOf(key), String.valueOf(val)));
            }

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                totalEntries++;

                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);

                if (name.endsWith(".class")) {
                    classCount++;
                    try (var in = jar.getInputStream(entry)) {
                        byte[] header = in.readNBytes(8);
                        if (header.length >= 8) {
                            int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                            versionDist.put(major, versionDist.getOrDefault(major, 0) + 1);
                        }
                    }
                } else {
                    resourceCount++;
                    if (name.startsWith("META-INF/services/")) {
                        services.add(name);
                    } else if (lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".war")) {
                        jars.add(name);
                    } else if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib") || lower.endsWith(".jnilib")) {
                        natives.add(name);
                    } else if (lower.endsWith(".properties") || lower.endsWith(".xml") || lower.endsWith(".json")
                            || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".conf")) {
                        configs.add(name);
                    } else if (name.startsWith("META-INF/") && (lower.endsWith(".sf") || lower.endsWith(".dsa")
                            || lower.endsWith(".rsa") || lower.endsWith(".ec") || lower.endsWith("manifest.mf"))) {
                        certs.add(name);
                    }
                }
            }
        }

        return new ProjectInventory(
                normalized.getFileName().toString(),
                normalized.toFile().length(),
                totalEntries,
                classCount,
                resourceCount,
                Collections.unmodifiableMap(manifestAttrs),
                Collections.unmodifiableList(services),
                Collections.unmodifiableList(jars),
                Collections.unmodifiableList(natives),
                Collections.unmodifiableList(configs),
                Collections.unmodifiableList(certs),
                Collections.unmodifiableMap(versionDist)
        );
    }

    public String toJson() {
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"archiveName\": \"").append(escape(archiveName)).append("\",\n");
        json.append("  \"totalSize\": ").append(totalSize).append(",\n");
        json.append("  \"totalEntries\": ").append(totalEntries).append(",\n");
        json.append("  \"classCount\": ").append(classCount).append(",\n");
        json.append("  \"resourceCount\": ").append(resourceCount).append(",\n");

        json.append("  \"manifestAttributes\": {\n");
        int i = 0;
        for (var entry : manifestAttributes.entrySet()) {
            json.append("    \"").append(escape(entry.getKey())).append("\": \"")
                    .append(escape(entry.getValue())).append("\"");
            if (++i < manifestAttributes.size()) json.append(",");
            json.append("\n");
        }
        json.append("  },\n");

        appendArray(json, "serviceProviders", serviceProviders, true);
        appendArray(json, "embeddedJars", embeddedJars, true);
        appendArray(json, "nativeLibraries", nativeLibraries, true);
        appendArray(json, "configurationFiles", configurationFiles, true);
        appendArray(json, "certificatesAndSignatures", certificatesAndSignatures, true);

        json.append("  \"classVersionDistribution\": {\n");
        i = 0;
        for (var entry : classVersionDistribution.entrySet()) {
            json.append("    \"Java ").append(javaVersion(entry.getKey())).append(" (major ").append(entry.getKey()).append(")\": ")
                    .append(entry.getValue());
            if (++i < classVersionDistribution.size()) json.append(",");
            json.append("\n");
        }
        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    private static void appendArray(StringBuilder sb, String key, List<String> items, boolean trailingComma) {
        sb.append("  \"").append(key).append("\": [\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append("    \"").append(escape(items.get(i))).append("\"");
            if (i < items.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]").append(trailingComma ? "," : "").append("\n");
    }

    private static String escape(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String javaVersion(int major) {
        if (major >= 45 && major <= 69) {
            return String.valueOf(major - 44);
        }
        return String.valueOf(major);
    }
}
