package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class MethodRenameTransformer extends Transformer {

    @Override
    public String getName() {
        return "method-rename";
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String mode = config.getOption("mode", "aggressive").toLowerCase();
        boolean safe = mode.equals("safe");

        MethodNameAllocator names = mappings.methodNames(config.getDictionary(), pool.getClasses());
        pool.getHierarchy().refreshMethodIndex();
        Map<String, Set<String>> overrideGroups = new HashMap<>();
        int renamedMethods = 0;

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            for (MethodNode method : classNode.methods) {
                if (mappings.isMethodPreserved(classNode.name, method.name, method.desc)) {
                    continue;
                }

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

                if (safe && shouldKeepSafe(method, classNode)) {
                    continue;
                }

                if (pool.getHierarchy().methodOverridesLibrary(classNode.name, method.name, method.desc)) {
                    continue;
                }

                String overrideKey = mappingKey(classNode.name, method.name, method.desc);
                Set<String> overrideGroup = overrideGroups.get(overrideKey);
                if (overrideGroup == null) {
                    Set<String> discovered = pool.getHierarchy().getOverrideGroup(classNode.name, method.name, method.desc);
                    discovered.removeIf(owner -> pool.isTransformationExcluded(owner)
                            || pool.getLibraryClasses().containsKey(owner)
                            || !shouldProcess(owner, config,
                            pool.getGlobalExclusions(), pool.getGlobalInclusions()));
                    overrideGroup = Set.copyOf(discovered);
                    for (String owner : overrideGroup) {
                        overrideGroups.putIfAbsent(mappingKey(owner, method.name, method.desc), overrideGroup);
                    }
                }
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

                String newName = names.next(overrideGroup, method.desc);
                if (!newName.equals(method.name)) {
                    for (String member : overrideGroup) {
                        mappings.mapMethod(member, method.name, method.desc, newName);
                    }
                    renamedMethods += overrideGroup.size();
                }
            }
        }

        log("Collected {} method mapping(s) across {} classes", renamedMethods, names.ownerCount());
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

    private static String mappingKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }
}
