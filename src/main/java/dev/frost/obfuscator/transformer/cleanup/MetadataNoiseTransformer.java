package dev.frost.obfuscator.transformer.cleanup;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MetadataNoiseTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "metadata-noise";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int stringsPerClass = Math.max(0, getIntOption(config, "strings-per-class", 8));
        boolean deprecated = getBooleanOption(config, "deprecated", true);
        boolean signatures = getBooleanOption(config, "signatures", true);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)
                    || AccessHelper.isAnnotation(classNode.access)) {
                continue;
            }

            if (deprecated) {
                classNode.access |= Opcodes.ACC_DEPRECATED;
            }
            if (signatures && classNode.signature == null && RANDOM.nextBoolean()) {
                classNode.signature = "L" + classNode.name + ";";
            }

            if (stringsPerClass > 0) {
                classNode.methods.add(buildNoiseMethod(uniqueMethodName(classNode), stringsPerClass));
            }
            pool.markDirty(classNode.name);
            log("Added metadata and constant-pool noise to {}", classNode.name);
        }
    }

    private MethodNode buildNoiseMethod(String name, int strings) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "()V", null, null);
        InsnList il = method.instructions;
        for (int i = 0; i < strings; i++) {
            il.add(new LdcInsnNode(randomNoiseString()));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new LdcInsnNode(RANDOM.nextInt()));
            il.add(new InsnNode(Opcodes.POP));
        }
        il.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        return method;
    }

    private String randomNoiseString() {
        int length = 12 + RANDOM.nextInt(24);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int bucket = RANDOM.nextInt(4);
            char c = switch (bucket) {
                case 0 -> (char) ('A' + RANDOM.nextInt(26));
                case 1 -> (char) ('a' + RANDOM.nextInt(26));
                case 2 -> (char) ('0' + RANDOM.nextInt(10));
                default -> (char) (0x2500 + RANDOM.nextInt(0x80));
            };
            builder.append(c);
        }
        return builder.toString();
    }

    private String uniqueMethodName(ClassNode classNode) {
        List<String> names = new ArrayList<>();
        for (MethodNode method : classNode.methods) names.add(method.name);
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

    private boolean getBooleanOption(TransformerConfig config, String key, boolean defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return defaultValue;
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
}
