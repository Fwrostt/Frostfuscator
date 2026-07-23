package dev.frost.obfuscator.transformer.funsies;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Uses legal JVM identifiers which resemble compiler output from another language.
 */
public final class LanguageMixupTransformer extends Transformer {
    @Override
    public String getName() {
        return "language-mixup";
    }

    @Override
    public String getCategory() {
        return "Funsies";
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String style = config.getOption("style", "mixed").toLowerCase(Locale.ROOT);
        boolean classes = booleanOption(config, "rename-classes", true);
        boolean methods = booleanOption(config, "rename-methods", true);
        int classIndex = 0;
        int methodIndex = 0;
        Set<String> reserved = new HashSet<>(pool.getClassMap().keySet());
        reserved.addAll(pool.getLibraryClasses().keySet());

        for (ClassNode node : pool.getClasses()) {
            if (!shouldProcess(node.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }
            if (classes && !mappings.hasClassMapping(node.name)) {
                String packageName = node.name.contains("/")
                        ? node.name.substring(0, node.name.lastIndexOf('/') + 1) : "";
                String candidate;
                do {
                    candidate = packageName + className(style, classIndex++);
                } while (reserved.contains(candidate));
                mappings.mapClass(node.name, candidate);
                reserved.add(candidate);
            }

            if (!methods) {
                continue;
            }
            Set<String> used = new HashSet<>();
            for (MethodNode method : node.methods) {
                used.add(method.name + method.desc);
            }
            for (MethodNode method : node.methods) {
                if (AccessHelper.isInitializer(method)
                        || AccessHelper.isMainMethod(method)
                        || AccessHelper.isEnumMethod(method, node)
                        || isExcludedMember(method.name, config)
                        || (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0
                        || pool.getHierarchy().methodOverridesLibrary(node.name, method.name, method.desc)
                        || mappings.hasMethodMapping(node.name, method.name, method.desc)) {
                    continue;
                }
                String candidate;
                do {
                    candidate = methodName(style, methodIndex++);
                } while (used.contains(candidate + method.desc));
                Set<String> overrideGroup = pool.getHierarchy()
                        .getOverrideGroup(node.name, method.name, method.desc);
                overrideGroup.removeIf(owner -> !shouldProcess(owner, config,
                        pool.getGlobalExclusions(), pool.getGlobalInclusions()));
                for (String owner : overrideGroup) {
                    mappings.mapMethod(owner, method.name, method.desc, candidate);
                }
                used.add(candidate + method.desc);
            }
        }
        log("Created {} class and {} method names using the {} style", classIndex, methodIndex, style);
    }

    private String className(String style, int index) {
        return switch (style) {
            case "cpp" -> "_ZN5frost" + index + "E";
            case "kotlin" -> "FrostKt$WhenMappings$" + index;
            case "scala" -> "Frost$anon$" + index + "$class";
            default -> index % 3 == 0 ? "_ZN5frost" + index + "E"
                    : index % 3 == 1 ? "FrostKt$WhenMappings$" + index
                    : "Frost$anon$" + index + "$class";
        };
    }

    private String methodName(String style, int index) {
        return switch (style) {
            case "cpp" -> "_Z" + (6 + Integer.toString(index).length()) + "frost_" + index + "ii";
            case "kotlin" -> "access$frost_" + index + "$p";
            case "scala" -> "frost$adapted$" + index;
            default -> index % 3 == 0 ? "_Z6frost_" + index + "ii"
                    : index % 3 == 1 ? "access$frost_" + index + "$p"
                    : "frost$adapted$" + index;
        };
    }

    private boolean booleanOption(TransformerConfig config, String key, boolean fallback) {
        Object value = config.getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private Set<String> collectReflectiveClassNames(ClassPool pool) {
        Set<String> names = new HashSet<>();
        for (ClassNode classNode : pool.getClasses()) {
            if (classNode.methods == null) continue;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc) {
                        if (ldc.cst instanceof String value) {
                            names.add(value.replace('.', '/'));
                        } else if (ldc.cst instanceof org.objectweb.asm.Type type && type.getSort() == org.objectweb.asm.Type.OBJECT) {
                            names.add(type.getInternalName());
                        }
                    }
                }
            }
        }
        return names;
    }

    private Set<String> collectReflectiveMethodNames(ClassPool pool) {
        Set<String> names = new HashSet<>();
        for (ClassNode classNode : pool.getClasses()) {
            if (classNode.methods == null) continue;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                        names.add(value);
                    }
                }
            }
        }
        return names;
    }
}
