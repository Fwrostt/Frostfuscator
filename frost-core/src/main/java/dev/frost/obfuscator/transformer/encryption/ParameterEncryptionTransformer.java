package dev.frost.obfuscator.transformer.encryption;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.util.AccessHelper;
import dev.frost.ir.pass.ParameterEncryptionPass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();
        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                return;
            }

            Map<ParameterEncryptionPass.MethodRef, Map<Integer, Long>> targets = new LinkedHashMap<>();
            for (MethodNode method : classNode.methods) {
                if (canEncrypt(method) && RANDOM.nextInt(100) < probability) {
                    Map<Integer, Long> keys = new LinkedHashMap<>();
                    Type[] arguments = Type.getArgumentTypes(method.desc);
                    for (int argument = 0; argument < arguments.length; argument++) {
                        if (encryptable(arguments[argument])) keys.put(argument, nonZeroKey(arguments[argument]));
                    }
                    if (!keys.isEmpty()) targets.put(ref(classNode.name, method), Map.copyOf(keys));
                }
            }
            if (targets.isEmpty()) return;

            List<StagedMethod> staged = new ArrayList<>();
            long callsites = 0;
            for (MethodNode method : classNode.methods) {
                Map<Integer, Long> entry = targets.get(ref(classNode.name, method));
                boolean callsTarget = containsTargetCall(method, targets);
                if (entry == null && !callsTarget) continue;
                var pass = ParameterEncryptionPass.rewrite(entry == null ? Map.of() : entry, targets);
                var result = adapter.run(classNode.name, method, pass,
                        RANDOM.nextLong() ^ method.name.hashCode() ^ method.desc.hashCode());
                if (!result.changed()) {
                    staged.clear();
                    return;
                }
                staged.add(new StagedMethod(method, result.output().orElseThrow()));
                callsites += result.metric("callsites");
            }
            if (staged.isEmpty()) return;
            staged.forEach(item -> IrMethodPassAdapter.publishBody(item.target(), item.output()));
            pool.markFramesDirty(classNode.name);
            detail("Encrypted {} parameters across {} entries and {} callsites in {}",
                    targets.values().stream().mapToInt(Map::size).sum(), targets.size(), callsites, classNode.name);
        });
    }

    private boolean containsTargetCall(MethodNode method,
                                       Map<ParameterEncryptionPass.MethodRef, Map<Integer, Long>> targets) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && targets.containsKey(new ParameterEncryptionPass.MethodRef(
                    call.owner, call.name, call.desc))) {
                return true;
            }
        }
        return false;
    }

    private boolean canEncrypt(MethodNode method) {
        if (AccessHelper.isInitializer(method)) return false;
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0) return false;
        if (!AccessHelper.isPrivate(method.access) || !AccessHelper.isStatic(method.access)) return false;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (encryptable(argument)) return true;
        }
        return false;
    }

    private boolean encryptable(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT, Type.LONG -> true;
            default -> false;
        };
    }

    private long nonZeroKey(Type type) {
        if (type.getSort() == Type.LONG) {
            long value;
            do value = RANDOM.nextLong(); while (value == 0L);
            return value;
        }
        return nonZeroRandom();
    }

    private ParameterEncryptionPass.MethodRef ref(String owner, MethodNode method) {
        return new ParameterEncryptionPass.MethodRef(owner, method.name, method.desc);
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

    private record StagedMethod(MethodNode target, MethodNode output) {}
}
