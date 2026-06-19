package dev.frost.obfuscator.transformer.cleanup;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

public class RemoveDebugTransformer extends Transformer {

    @Override
    public String getName() {
        return "remove-debug";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        boolean removeSourceFile = getBooleanOption(config, "remove-source-file", true);
        boolean removeLineNumbers = getBooleanOption(config, "remove-line-numbers", true);
        boolean removeLocalVariables = getBooleanOption(config, "remove-local-variables", true);
        boolean removeParameters = getBooleanOption(config, "remove-parameters", true);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }
            boolean changed = false;

            if (removeSourceFile) {
                changed |= classNode.sourceFile != null || classNode.sourceDebug != null;
                classNode.sourceFile = null;
                classNode.sourceDebug = null;
            }

            for (MethodNode method : classNode.methods) {
                if (removeParameters && method.parameters != null) {
                    changed |= !method.parameters.isEmpty();
                    method.parameters.clear();
                }

                if (removeLocalVariables && method.localVariables != null) {
                    changed |= !method.localVariables.isEmpty();
                    method.localVariables.clear();
                }

                if (removeLineNumbers && method.instructions != null) {
                    var insn = method.instructions.getFirst();
                    while (insn != null) {
                        var next = insn.getNext();
                        if (insn instanceof LineNumberNode) {
                            method.instructions.remove(insn);
                            changed = true;
                        }
                        insn = next;
                    }
                }

                if (method.tryCatchBlocks != null) {
                    for (var tcb : method.tryCatchBlocks) {
                        if (tcb.visibleTypeAnnotations != null) {
                            tcb.visibleTypeAnnotations.clear();
                        }
                        if (tcb.invisibleTypeAnnotations != null) {
                            tcb.invisibleTypeAnnotations.clear();
                        }
                    }
                }
            }

            if (classNode.visibleAnnotations != null) {
                changed |= classNode.visibleAnnotations.removeIf(a -> a.desc.equals("Lkotlin/Metadata;"));
            }
            if (classNode.invisibleAnnotations != null) {
                changed |= classNode.invisibleAnnotations.removeIf(a -> a.desc.equals("Lkotlin/Metadata;"));
            }
            if (changed) {
                pool.markDirty(classNode.name);
            }
        }
    }

    private boolean getBooleanOption(TransformerConfig config, String key, boolean defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return defaultValue;
    }
}
