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
        Set<String> reflectiveClassNames = collectReflectiveClassNames(pool);
        reflectiveClassNames.forEach(mappings::preserveClass);
        Set<String> reservedNames = new HashSet<>();
        reservedNames.addAll(pool.getClassMap().keySet());
        reservedNames.addAll(pool.getLibraryClasses().keySet());

        String packageMode = pool.getPackageMode();
        String flattenPackage = sanitizePackage(pool.getFlattenPackage());
        int renamedClasses = 0;

        for (ClassNode classNode : pool.getClasses()) {
            String originalName = classNode.name;

            if (originalName.equals("module-info") || originalName.endsWith("/package-info")) {
                continue;
            }

            if (!shouldProcess(originalName, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }
            if (mappings.hasClassMapping(originalName)) {
                continue;
            }
            if (mappings.isClassPreserved(originalName)) {
                detail("Keeping reflectively referenced class {}", originalName);
                continue;
            }

            if (safe && shouldKeepSafe(classNode)) {
                detail("Skipping safe-target class {}", originalName);
                continue;
            }

            String newName = generateName(originalName, packageMode, flattenPackage, dictionary, reservedNames);
            if (!newName.equals(originalName)) {
                mappings.mapClass(originalName, newName);
                reservedNames.add(newName);
                renamedClasses++;
            }
        }
        log("Collected {} class mapping(s)", renamedClasses);
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

    private Set<String> collectReflectiveClassNames(ClassPool pool) {
        Set<String> names = new HashSet<>();
        for (ClassNode classNode : pool.getClasses()) {
            if (classNode.methods == null) continue;
            for (org.objectweb.asm.tree.MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;
                for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
                        String className = null;
                        if (ldc.cst instanceof String value) {
                            className = value.replace('.', '/');
                        }
                        if (className != null && pool.contains(className)) {
                            names.add(className);
                        }
                    }
                }
            }
        }
        return names;
    }
}
