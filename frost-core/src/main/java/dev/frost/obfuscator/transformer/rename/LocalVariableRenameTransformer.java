package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.dictionary.Dictionary;
import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LocalVariableRenameTransformer extends Transformer {

    @Override
    public String getName() {
        return "local-variable-rename";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        Dictionary dictionary = Dictionary.create(config.getDictionary());
        Set<String> usedGlobal = new HashSet<>();
        Map<String, String> nameMap = new HashMap<>();

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            boolean changed = false;
            for (MethodNode method : classNode.methods) {
                if (method.localVariables == null) continue;

                for (LocalVariableNode lv : method.localVariables) {
                    if (lv.index == 0 && !isStatic(method)) continue;
                    if (isExcludedMember(lv.name, config)) continue;

                    String newName = nameMap.computeIfAbsent(lv.name, k -> generateName(dictionary, usedGlobal));
                    lv.name = newName;
                    changed = true;
                }
            }
            if (changed) {
                pool.markDirty(classNode.name);
            }
        }
    }

    private boolean isStatic(org.objectweb.asm.tree.MethodNode method) {
        return (method.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
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
