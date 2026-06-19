package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.dictionary.Dictionary;
import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.*;

public class ClassRenameTransformer extends Transformer {

    private static final Set<String> ANNOTATIONS_SAFE = Set.of(
            "Lkotlin/Metadata;",
            "Lkotlin/coroutines/jvm/internal/DebugMetadata;"
    );

    @Override
    public String getName() {
        return "class-rename";
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String mode = config.getOption("mode", "aggressive").toLowerCase();
        boolean safe = mode.equals("safe");

        Dictionary dictionary = Dictionary.create(config.getDictionary());
        Set<String> reservedNames = new HashSet<>();
        reservedNames.addAll(pool.getClassMap().keySet());
        reservedNames.addAll(pool.getLibraryClasses().keySet());

        String packageMode = pool.getPackageMode();
        String flattenPackage = sanitizePackage(pool.getFlattenPackage());

        for (ClassNode classNode : pool.getClasses()) {
            String originalName = classNode.name;

            if (originalName.equals("module-info") || originalName.endsWith("/package-info")) {
                continue;
            }

            if (!shouldProcess(originalName, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            if (safe && shouldKeepSafe(classNode)) {
                log("Skipping safe-target class {}", originalName);
                continue;
            }

            String newName = generateName(originalName, packageMode, flattenPackage, dictionary, reservedNames);
            if (!newName.equals(originalName)) {
                mappings.mapClass(originalName, newName);
                reservedNames.add(newName);
                log("Renamed {} -> {}", originalName, newName);
            }
        }
    }

    private boolean shouldKeepSafe(ClassNode classNode) {
        int access = classNode.access;

        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return true;
        }

        if (classNode.visibleAnnotations != null) {
            for (var annotation : classNode.visibleAnnotations) {
                if (ANNOTATIONS_SAFE.contains(annotation.desc)) {
                    return true;
                }
            }
        }

        if (classNode.invisibleAnnotations != null) {
            for (var annotation : classNode.invisibleAnnotations) {
                if (ANNOTATIONS_SAFE.contains(annotation.desc)) {
                    return true;
                }
            }
        }

        return false;
    }

    private String generateName(String originalName, String packageMode, String flattenPackage,
                                Dictionary dictionary, Set<String> reservedNames) {
        String baseName = dictionary.next();
        String candidate = buildName(originalName, packageMode, flattenPackage, baseName);
        while (!candidate.equals(originalName) && reservedNames.contains(candidate)) {
            baseName = dictionary.next();
            candidate = buildName(originalName, packageMode, flattenPackage, baseName);
        }
        return candidate;
    }

    private String buildName(String originalName, String packageMode, String flattenPackage, String baseName) {
        return switch (packageMode.toLowerCase()) {
            case "flatten" -> flattenPackage + "/" + baseName;
            case "remove" -> baseName;
            default -> keepPackage(originalName, baseName);
        };
    }

    private String keepPackage(String originalName, String newSimpleName) {
        int lastSlash = originalName.lastIndexOf('/');
        if (lastSlash == -1) {
            return newSimpleName;
        }
        return originalName.substring(0, lastSlash + 1) + newSimpleName;
    }

    private String sanitizePackage(String flattenPackage) {
        if (flattenPackage == null || flattenPackage.isBlank()) {
            return "obf";
        }
        return flattenPackage.replace('.', '/').replaceAll("[^a-zA-Z0-9_/]", "");
    }
}
