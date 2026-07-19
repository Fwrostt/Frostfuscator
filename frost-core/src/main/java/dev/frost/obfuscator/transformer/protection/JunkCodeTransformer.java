package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class JunkCodeTransformer extends Transformer {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "junk-code";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        int minMethods = clamp(getIntOption(context, "min-methods-per-class", getIntOption(context, "methods-per-class", 1)), 0, 64);
        int maxMethods = clamp(getIntOption(context, "max-methods-per-class", getIntOption(context, "methods-per-class", 3)), minMethods, 64);
        int minFields = clamp(getIntOption(context, "min-fields-per-class", getIntOption(context, "fields-per-class", 0)), 0, 32);
        int maxFields = clamp(getIntOption(context, "max-fields-per-class", getIntOption(context, "fields-per-class", 2)), minFields, 32);
        long seed = getLongOption(context, "seed", 0L);
        if (seed == 0L) {
            seed = SECURE_RANDOM.nextLong();
        }
        Random random = new Random(seed);
        int touched = 0;
        int members = 0;

        for (ClassNode classNode : context.pool().getClasses()) {
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())
                    || (classNode.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) {
                continue;
            }

            Set<String> names = existingNames(classNode);
            int fieldsPerClass = randomRange(random, minFields, maxFields);
            int methodsPerClass = randomRange(random, minMethods, maxMethods);
            for (int i = 0; i < fieldsPerClass; i++) {
                String name = uniqueName(names, random);
                classNode.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        name,
                        "Ljava/lang/String;",
                        null,
                        Integer.toHexString(random.nextInt())
                ));
                members++;
            }
            for (int i = 0; i < methodsPerClass; i++) {
                String name = uniqueName(names, random);
                classNode.methods.add(junkMethod(name, random));
                members++;
            }

            if (fieldsPerClass + methodsPerClass > 0) {
                context.pool().markDirty(classNode.name);
                touched++;
            }
        }

        context.stats().add("junkCodeClasses", touched);
        context.stats().add("junkCodeMembers", members);
        log("Added {} junk members to {} classes", members, touched);
    }

    private MethodNode junkMethod(String name, Random random) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "(Ljava/lang/String;I)Ljava/lang/String;",
                null,
                null
        );
        LabelNode nullLabel = new LabelNode();
        LabelNode end = new LabelNode();
        int salt = random.nextInt(255) + 1;
        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, nullLabel));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new LdcInsnNode(salt));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "reverse", "()Ljava/lang/StringBuilder;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(nullLabel);
        insns.add(new LdcInsnNode(Integer.toHexString(random.nextInt())));
        insns.add(end);
        insns.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 4;
        method.maxLocals = 2;
        return method;
    }

    private Set<String> existingNames(ClassNode classNode) {
        Set<String> names = new HashSet<>();
        for (FieldNode field : classNode.fields) {
            names.add(field.name);
        }
        for (MethodNode method : classNode.methods) {
            names.add(method.name);
        }
        return names;
    }

    private String uniqueName(Set<String> names, Random random) {
        String name;
        do {
            name = "__frost$" + Long.toHexString(random.nextLong());
        } while (!names.add(name));
        return name;
    }

    private int getIntOption(Context context, String key, int fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private long getLongOption(Context context, String key, long fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int randomRange(Random random, int min, int max) {
        return min == max ? min : min + random.nextInt(max - min + 1);
    }
}
