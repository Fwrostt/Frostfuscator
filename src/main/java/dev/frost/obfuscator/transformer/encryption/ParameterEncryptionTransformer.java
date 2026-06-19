package dev.frost.obfuscator.transformer.encryption;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class ParameterEncryptionTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "parameter-encryption";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 30), 0, 100);
        Map<String, Integer> keys = new HashMap<>();

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }
            for (MethodNode method : classNode.methods) {
                if (canEncrypt(method) && RANDOM.nextInt(100) < probability) {
                    int key = nonZeroRandom();
                    keys.put(methodKey(classNode.name, method.name, method.desc), key);
                    insertDecode(method, key);
                }
            }
        }

        if (keys.isEmpty()) return;

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            int changed = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;
                AbstractInsnNode insn = method.instructions.getFirst();
                while (insn != null) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                        Integer key = keys.get(methodKey(call.owner, call.name, call.desc));
                        if (key != null) {
                            InsnList encode = new InsnList();
                            encode.add(new LdcInsnNode(key));
                            encode.add(new InsnNode(Opcodes.IXOR));
                            method.instructions.insertBefore(call, encode);
                            changed++;
                        }
                    }
                    insn = next;
                }
            }

            if (changed > 0) {
                pool.markDirty(classNode.name);
                log("Encrypted {} single-int call arguments in {}", changed, classNode.name);
            }
        }
    }

    private boolean canEncrypt(MethodNode method) {
        if (AccessHelper.isInitializer(method)) return false;
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0) return false;
        if (!AccessHelper.isPrivate(method.access) || !AccessHelper.isStatic(method.access)) return false;
        Type[] args = Type.getArgumentTypes(method.desc);
        return args.length == 1 && args[0].getSort() == Type.INT;
    }

    private void insertDecode(MethodNode method, int key) {
        AbstractInsnNode anchor = firstExecutable(method);
        if (anchor == null) return;
        InsnList decode = new InsnList();
        decode.add(new VarInsnNode(Opcodes.ILOAD, 0));
        decode.add(new LdcInsnNode(key));
        decode.add(new InsnNode(Opcodes.IXOR));
        decode.add(new VarInsnNode(Opcodes.ISTORE, 0));
        method.instructions.insertBefore(anchor, decode);
    }

    private AbstractInsnNode firstExecutable(MethodNode method) {
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode)
                    && !(insn instanceof FrameNode)) {
                return insn;
            }
            insn = insn.getNext();
        }
        return null;
    }

    private String methodKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    private int nonZeroRandom() {
        int value;
        do {
            value = RANDOM.nextInt();
        } while (value == 0);
        return value;
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
