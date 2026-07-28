package dev.frost.obfuscator.engine;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Separates application-owned classes from dependency classes embedded in fat JARs.
 * Explicit library matches are handled while libraries are loaded; this detector covers
 * common shaded packages and very large archives with a declared entry point.
 */
public final class ApplicationClassDetector {
    private static final int KNOWN_LIBRARY_DETECTION_MINIMUM = 64;
    private static final int OWNERSHIP_DETECTION_MINIMUM = 1_000;
    private static final List<String> KNOWN_LIBRARY_PREFIXES = List.of(
            "atlantafx/", "ch/qos/logback/", "com/fasterxml/", "com/google/",
            "com/strobel/", "com/sun/javafx/", "com/sun/jna/", "io/netty/",
            "impl/org/", "javafx/", "kotlin/", "kotlinx/", "net/bytebuddy/",
            "net/fabricmc/", "org/apache/commons/", "org/benf/", "org/bouncycastle/",
            "org/controlsfx/", "org/fxmisc/", "org/intellij/", "org/jetbrains/",
            "org/jooq/", "org/junit/", "org/kordamp/", "org/lwjgl/", "org/objectweb/",
            "org/reactfx/", "org/slf4j/", "org/yaml/", "picocli/", "scala/"
    );
    private static final Set<String> REVERSE_DNS_ROOTS = Set.of(
            "com", "org", "net", "io", "dev", "me", "co", "app"
    );

    public DetectionResult detect(ClassPool pool, Collection<String> declaredEntrypoints) {
        Set<String> ownershipRoots = ownershipRoots(declaredEntrypoints);
        if (ownershipRoots.isEmpty() || pool.size() < KNOWN_LIBRARY_DETECTION_MINIMUM) {
            return new DetectionResult(0, Set.copyOf(ownershipRoots), Map.of());
        }

        boolean largeArchive = pool.size() >= OWNERSHIP_DETECTION_MINIMUM;
        Map<String, Integer> families = new LinkedHashMap<>();
        int excluded = 0;
        for (ClassNode classNode : new ArrayList<>(pool.getClasses())) {
            pool.cancellation().throwIfCancelled();
            String name = classNode.name;
            if (belongsTo(name, ownershipRoots)) continue;

            String knownPrefix = matchingKnownPrefix(name);
            String reason;
            String family;
            if (knownPrefix != null) {
                reason = "detected shaded library package " + knownPrefix;
                family = knownPrefix;
            } else if (largeArchive) {
                family = packageFamily(name);
                reason = "outside detected application ownership roots " + ownershipRoots;
            } else {
                continue;
            }

            if (pool.excludeFromTransformation(name, reason)) {
                excluded++;
                families.merge(family, 1, Integer::sum);
            }
        }
        return new DetectionResult(excluded, Set.copyOf(ownershipRoots), Map.copyOf(families));
    }

    private Set<String> ownershipRoots(Collection<String> entrypoints) {
        Set<String> roots = new LinkedHashSet<>();
        if (entrypoints == null) return roots;
        for (String raw : entrypoints) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim().replace('.', '/');
            int adapter = name.indexOf("::");
            if (adapter >= 0) name = name.substring(0, adapter);
            int inner = name.indexOf('$');
            if (inner >= 0) name = name.substring(0, inner);
            String[] parts = name.split("/");
            if (parts.length < 2) continue;
            int packageParts = parts.length - 1;
            int rootParts;
            String first = parts[0].toLowerCase(Locale.ROOT);
            if (REVERSE_DNS_ROOTS.contains(first)) {
                boolean hostedNamespace = parts.length > 3
                        && (parts[1].equalsIgnoreCase("github") || parts[1].equalsIgnoreCase("gitlab"));
                rootParts = Math.min(packageParts, hostedNamespace ? 3 : 2);
            } else {
                rootParts = 1;
            }
            if (rootParts <= 0) continue;
            roots.add(String.join("/", java.util.Arrays.copyOf(parts, rootParts)) + "/");
        }
        return roots;
    }

    private boolean belongsTo(String className, Set<String> roots) {
        for (String root : roots) {
            if (className.startsWith(root)) return true;
        }
        return false;
    }

    private String matchingKnownPrefix(String className) {
        for (String prefix : KNOWN_LIBRARY_PREFIXES) {
            if (className.startsWith(prefix)) return prefix;
        }
        return null;
    }

    private String packageFamily(String className) {
        String[] parts = className.split("/");
        if (parts.length < 2) return "(default package)";
        return parts[0] + "/" + parts[1] + "/";
    }

    public record DetectionResult(int excludedClasses, Set<String> ownershipRoots,
                                  Map<String, Integer> excludedFamilies) {
    }
}
