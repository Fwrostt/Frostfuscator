package dev.frost.obfuscator.transformer.cleanup;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public class AccessModifierTransformer extends Transformer {

    @Override
    public String getName() {
        return "access-modifier";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        boolean synthetic = getBooleanOption(config, "synthetic", true);
        boolean bridge = getBooleanOption(config, "bridge-methods", false);
        boolean relaxFinal = getBooleanOption(config, "relax-final", false);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }
            if (AccessHelper.isAnnotation(classNode.access) || AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            if (synthetic) {
                classNode.access |= Opcodes.ACC_SYNTHETIC;
            }
            if (relaxFinal) {
                classNode.access &= ~Opcodes.ACC_FINAL;
            }

            for (FieldNode field : classNode.fields) {
                if (isExcludedMember(field.name, config)) continue;
                if (synthetic) field.access |= Opcodes.ACC_SYNTHETIC;
                if (relaxFinal && !AccessHelper.isEnum(classNode)) field.access &= ~Opcodes.ACC_FINAL;
            }

            for (MethodNode method : classNode.methods) {
                if (AccessHelper.isInitializer(method) || isExcludedMember(method.name, config)) continue;
                if (synthetic) method.access |= Opcodes.ACC_SYNTHETIC;
                if (bridge && !AccessHelper.isStatic(method.access) && !AccessHelper.isAbstract(method.access)
                        && !AccessHelper.isNative(method.access)) {
                    method.access |= Opcodes.ACC_BRIDGE;
                }
                if (relaxFinal) method.access &= ~Opcodes.ACC_FINAL;
            }
            if (synthetic || bridge || relaxFinal) {
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
