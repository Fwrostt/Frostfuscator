package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

public class FlowOutlinerTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "flow-outliner";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        transformInternal(pool, mappings, config, null);
    }

    @Override
    public void transform(Context context) {
        transformInternal(context.pool(), context.mappings(), context.config(), context);
    }

    private void transformInternal(ClassPool pool, MappingCollector mappings,
                                   TransformerConfig config, Context context) {
        int probability = clamp(getIntOption(config, "probability", 25), 0, 100);
        int maxPerClass = Math.max(0, getIntOption(config, "max-per-class", 16));
        LongAdder outlinedCount = new LongAdder();
        LongAdder retainedCount = new LongAdder();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                return;
            }

            int changed = 0;
            List<MethodNode> additions = new ArrayList<>();
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (changed >= maxPerClass) break;
                if (!canOutline(method) || RANDOM.nextInt(100) >= probability) continue;

                try {
                    String outlinedName = uniqueMethodName(classNode, additions);
                    MethodNode outlined = cloneAsOutlined(method, outlinedName);
                    replaceWithDelegate(classNode.name, method, outlinedName);
                    additions.add(outlined);
                    changed++;
                } catch (RuntimeException exception) {
                    retainedCount.increment();
                    detail("Retained method {}{} in {} because its instruction metadata could not be outlined",
                            method.name, method.desc, classNode.name);
                }
            }

            if (!additions.isEmpty()) {
                classNode.methods.addAll(additions);
                pool.markFramesDirty(classNode.name);
                outlinedCount.add(changed);
                detail("Outlined {} method bodies in {}", changed, classNode.name);
            }
        });
        if (context != null) context.stats().add("outlinedMethods", outlinedCount.sum());
        if (retainedCount.sum() > 0) {
            log("Safely retained {} method(s) with incompatible instruction metadata", retainedCount.sum());
        }
    }

    private boolean canOutline(MethodNode method) {
        if (method == null || method.name == null || method.desc == null) return false;
        if (AccessHelper.isInitializer(method)) return false;
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if ((method.access & Opcodes.ACC_STATIC) == 0) return false;
        if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) return false;
        if (method.instructions == null || method.instructions.size() < 8) return false;
        return method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty();
    }

    private MethodNode cloneAsOutlined(MethodNode original, String name) {
        MethodNode outlined = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, original.desc, original.signature, exceptions(original));
        // Let ASM remap every label referenced by jumps, switches, frames, and line metadata.
        // Manual LabelNode maps can produce null clones when an earlier pass removed a label.
        original.instructions.accept(outlined);
        outlined.maxLocals = original.maxLocals;
        outlined.maxStack = original.maxStack;
        return outlined;
    }

    private String[] exceptions(MethodNode method) {
        if (method.exceptions == null || method.exceptions.isEmpty()) return null;
        return method.exceptions.toArray(new String[0]);
    }

    private void replaceWithDelegate(String owner, MethodNode method, String outlinedName) {
        InsnList delegate = new InsnList();
        int slot = 0;
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            delegate.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
            slot += arg.getSize();
        }
        delegate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, outlinedName, method.desc, false));
        delegate.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));

        // Commit only after the complete delegate has been built, so a rejected method is unchanged.
        method.instructions = delegate;
        method.tryCatchBlocks = new ArrayList<>();
        method.localVariables = null;
        method.maxLocals = slot;
        method.maxStack = Math.max(1, slot + 1);
    }

    private String uniqueMethodName(ClassNode classNode, List<MethodNode> additions) {
        Set<String> names = new HashSet<>();
        for (MethodNode method : classNode.methods) names.add(method.name);
        for (MethodNode method : additions) names.add(method.name);
        String name;
        do {
            name = randomIdentifier();
        } while (names.contains(name));
        return name;
    }

    private String randomIdentifier() {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_";
        String body = alphabet + "0123456789";
        int length = 4 + RANDOM.nextInt(7);
        StringBuilder builder = new StringBuilder(length);
        builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        for (int i = 1; i < length; i++) {
            builder.append(body.charAt(RANDOM.nextInt(body.length())));
        }
        return builder.toString();
    }

    private int getIntOption(TransformerConfig config, String key, int defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
