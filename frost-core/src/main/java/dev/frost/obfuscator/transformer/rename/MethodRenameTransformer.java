package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.dictionary.Dictionary;
import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MethodRenameTransformer extends Transformer {

    @Override
    public String getName() {
        return "method-rename";
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String mode = config.getOption("mode", "aggressive").toLowerCase();
        boolean safe = mode.equals("safe");

        Dictionary dictionary = Dictionary.create(config.getDictionary());
        Map<String, Set<String>> usedNamesPerClass = new HashMap<>();
        Set<String> reflectiveMethodNames = collectReflectiveMethodNames(pool);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            Set<String> used = usedNamesPerClass.computeIfAbsent(classNode.name, k -> new HashSet<>());
            for (MethodNode existing : classNode.methods) {
                used.add(methodKey(existing.name, existing.desc));
            }

            for (MethodNode method : classNode.methods) {
                if (AccessHelper.isInitializer(method)) {
                    continue;
                }

                if (AccessHelper.isMainMethod(method)) {
                    continue;
                }

                if (AccessHelper.isEnumMethod(method, classNode)) {
                    continue;
                }

                if (isExcludedMember(method.name, config)) {
                    continue;
                }

                if (reflectiveMethodNames.contains(method.name)) {
                    continue;
                }

                if (safe && shouldKeepSafe(method, classNode)) {
                    continue;
                }

                if (pool.getHierarchy().methodOverridesLibrary(classNode.name, method.name, method.desc)) {
                    continue;
                }

                String overrideKey = classNode.name + "." + method.name + method.desc;
                Set<String> overrideGroup = pool.getHierarchy().getOverrideGroup(classNode.name, method.name, method.desc);
                overrideGroup.removeIf(owner -> !shouldProcess(owner, config, pool.getGlobalExclusions(), pool.getGlobalInclusions()));
                boolean alreadyMapped = false;
                for (String member : overrideGroup) {
                    if (mappings.hasMethodMapping(member, method.name, method.desc)) {
                        alreadyMapped = true;
                        break;
                    }
                }
                if (alreadyMapped) {
                    continue;
                }

                String newName = generateName(dictionary, used, method.desc);
                if (!newName.equals(method.name)) {
                    for (String member : overrideGroup) {
                        mappings.mapMethod(member, method.name, method.desc, newName);
                    }
                    log("Renamed method {}.{} -> {}", classNode.name, method.name, newName);
                }
            }
        }
    }

    private Set<String> collectReflectiveMethodNames(ClassPool pool) {
        Set<String> names = new HashSet<>();
        for (ClassNode classNode : pool.getClasses()) {
            boolean usesMethodReflection = false;
            Set<String> classStrings = new HashSet<>();

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;
                AbstractInsnNode insn = method.instructions.getFirst();
                while (insn != null) {
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                        classStrings.add(value);
                    } else if (insn instanceof MethodInsnNode call
                            && call.owner.equals("java/lang/Class")
                            && (call.name.equals("getMethod") || call.name.equals("getDeclaredMethod"))) {
                        usesMethodReflection = true;
                    }
                    insn = insn.getNext();
                }
            }

            if (usesMethodReflection) {
                names.addAll(classStrings);
            }
        }
        return names;
    }

    private boolean shouldKeepSafe(MethodNode method, ClassNode owner) {
        int access = method.access;
        if (AccessHelper.isPublic(access) || AccessHelper.isProtected(access)) {
            return true;
        }
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            return true;
        }
        return false;
    }

    private String generateName(Dictionary dictionary, Set<String> used, String desc) {
        String name;
        do {
            name = dictionary.next();
        } while (used.contains(methodKey(name, desc)));
        used.add(methodKey(name, desc));
        return name;
    }

    private static String methodKey(String name, String desc) {
        return name + desc;
    }
}
