package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.dictionary.Dictionary;
import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.*;

public class FieldRenameTransformer extends Transformer {

    @Override
    public String getName() {
        return "field-rename";
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String mode = config.getOption("mode", "aggressive").toLowerCase();
        boolean safe = mode.equals("safe");

        Dictionary dictionary = Dictionary.create(config.getDictionary());
        Map<String, Set<String>> usedNamesPerClass = new HashMap<>();
        int renamedFields = 0;

        Set<String> reflectiveFieldNames = collectReflectiveFieldNames(pool);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            Set<String> used = usedNamesPerClass.computeIfAbsent(classNode.name, k -> new HashSet<>());
            for (FieldNode existing : classNode.fields) {
                used.add(existing.name);
            }

            for (FieldNode field : classNode.fields) {
                if (field.name.startsWith("frost$") || field.name.startsWith("__frost") || isExcludedMember(field.name, config)) {
                    continue;
                }

                if (AccessHelper.isSerialVersionUID(field)) {
                    continue;
                }

                if (safe && shouldKeepSafe(field)) {
                    continue;
                }

                String newName = generateName(dictionary, used);
                if (!newName.equals(field.name)) {
                    mappings.mapField(classNode.name, field.name, field.desc, newName);
                    renamedFields++;
                }
            }
        }
        log("Collected {} field mapping(s) across {} classes", renamedFields, usedNamesPerClass.size());
    }

    private boolean shouldKeepSafe(FieldNode field) {
        int access = field.access;
        if (AccessHelper.isPublic(access) || AccessHelper.isProtected(access)) {
            return true;
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return true;
        }
        return false;
    }

    private String generateName(Dictionary dictionary, Set<String> used) {
        String name;
        do {
            name = dictionary.next();
        } while (used.contains(name));
        used.add(name);
        return name;
    }

    private Set<String> collectReflectiveFieldNames(ClassPool pool) {
        Set<String> names = new HashSet<>();
        for (ClassNode classNode : pool.getClasses()) {
            if (classNode.methods == null) continue;
            for (org.objectweb.asm.tree.MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;
                for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof String value) {
                        names.add(value);
                    }
                }
            }
        }
        return names;
    }
}
