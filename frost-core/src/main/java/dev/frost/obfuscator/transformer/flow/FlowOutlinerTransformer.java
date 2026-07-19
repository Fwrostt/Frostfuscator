package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.*;

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
        int probability = clamp(getIntOption(config, "probability", 25), 0, 100);
        int maxPerClass = Math.max(0, getIntOption(config, "max-per-class", 16));

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            int changed = 0;
            List<MethodNode> additions = new ArrayList<>();
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (changed >= maxPerClass) break;
                if (!canOutline(method) || RANDOM.nextInt(100) >= probability) continue;

                String outlinedName = uniqueMethodName(classNode, additions);
                MethodNode outlined = cloneAsOutlined(method, outlinedName);
                replaceWithDelegate(classNode.name, method, outlinedName);
                additions.add(outlined);
                changed++;
            }

            if (!additions.isEmpty()) {
                classNode.methods.addAll(additions);
                pool.markDirty(classNode.name);
                log("Outlined {} method bodies in {}", changed, classNode.name);
            }
        }
    }

    private boolean canOutline(MethodNode method) {
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
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn = original.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        for (AbstractInsnNode insn = original.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            outlined.instructions.add(insn.clone(labels));
        }
        outlined.maxLocals = original.maxLocals;
        outlined.maxStack = original.maxStack;
        return outlined;
    }

    private String[] exceptions(MethodNode method) {
        if (method.exceptions == null || method.exceptions.isEmpty()) return null;
        return method.exceptions.toArray(new String[0]);
    }

    private void replaceWithDelegate(String owner, MethodNode method, String outlinedName) {
        method.instructions.clear();
        int slot = 0;
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            method.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
            slot += arg.getSize();
        }
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, outlinedName, method.desc, false));
        method.instructions.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        method.tryCatchBlocks.clear();
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
