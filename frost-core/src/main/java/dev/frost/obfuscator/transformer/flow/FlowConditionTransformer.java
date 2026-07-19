package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;

public class FlowConditionTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "flow-condition";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 25), 0, 100);
        int maxPerMethod = Math.max(0, getIntOption(config, "max-per-method", 16));

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            String trapField = ensureTrapField(classNode);
            int changed = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                int inserted = 0;
                AbstractInsnNode insn = method.instructions.getFirst();
                while (insn != null && inserted < maxPerMethod) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn instanceof JumpInsnNode jump && isConditional(jump.getOpcode())
                            && RANDOM.nextInt(100) < probability) {
                        method.instructions.insertBefore(insn, guard(classNode.name, trapField));
                        inserted++;
                    }
                    insn = next;
                }
                changed += inserted;
            }

            if (changed > 0) {
                pool.markDirty(classNode.name);
                log("Inserted {} conditional guards in {}", changed, classNode.name);
            }
        }
    }

    private boolean isConditional(int opcode) {
        return opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE
                || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL;
    }

    private InsnList guard(String owner, String trapField) {
        LabelNode join = new LabelNode(new Label());
        InsnList list = new InsnList();
        list.add(zeroPredicate(owner, trapField));
        list.add(new JumpInsnNode(Opcodes.IFEQ, join));
        list.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        list.add(new InsnNode(Opcodes.ATHROW));
        list.add(join);
        return list;
    }

    private InsnList zeroPredicate(String owner, String trapField) {
        InsnList list = new InsnList();
        list.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, trapField, "I"));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new InsnNode(Opcodes.IXOR));
        return list;
    }

    private String ensureTrapField(ClassNode classNode) {
        String name = uniqueFieldName(classNode);
        classNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                name, "I", null, null));

        MethodNode clinit = null;
        for (MethodNode method : classNode.methods) {
            if (method.name.equals("<clinit>")) {
                clinit = method;
                break;
            }
        }
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            classNode.methods.add(clinit);
        }

        InsnList init = new InsnList();
        init.add(new LdcInsnNode(Type.getObjectType(classNode.name)));
        init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode",
                "(Ljava/lang/Object;)I", false));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, name, "I"));
        clinit.instructions.insert(init);
        return name;
    }

    private String uniqueFieldName(ClassNode classNode) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (FieldNode field : classNode.fields) used.add(field.name);
        String name;
        do {
            name = randomIdentifier();
        } while (!used.add(name));
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
