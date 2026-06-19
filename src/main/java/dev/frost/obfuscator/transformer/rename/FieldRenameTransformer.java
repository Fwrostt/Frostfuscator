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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            Set<String> used = usedNamesPerClass.computeIfAbsent(classNode.name, k -> new HashSet<>());
            for (FieldNode existing : classNode.fields) {
                used.add(existing.name);
            }

            for (FieldNode field : classNode.fields) {
                if (isExcludedMember(field.name, config)) {
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
                    log("Renamed field {}.{} -> {}", classNode.name, field.name, newName);
                }
            }
        }
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
}
