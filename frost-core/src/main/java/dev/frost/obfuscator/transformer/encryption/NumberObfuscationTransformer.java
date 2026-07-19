package dev.frost.obfuscator.transformer.encryption;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

public class NumberObfuscationTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "number-obfuscation";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 80), 0, 100);
        int maxMethodInstructions = getIntOption(config, "max-method-instructions", 6000);
        int maxPerMethod = Math.max(0, getIntOption(config, "max-per-method", 96));
        int maxPerClass = Math.max(0, getIntOption(config, "max-per-class", 256));

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            int classSeed = RANDOM.nextInt();
            int classHash = classNode.name.replace('/', '.').hashCode() ^ classSeed;
            String intDecryptName = uniqueMethodName(classNode);
            String longDecryptName = uniqueMethodName(classNode);
            int changed = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
                if (method.instructions.size() > maxMethodInstructions) continue;
                if (changed >= maxPerClass) break;
                int changedInMethod = 0;
                AbstractInsnNode insn = method.instructions.getFirst();
                while (insn != null) {
                    AbstractInsnNode next = insn.getNext();
                    if (changed >= maxPerClass || changedInMethod >= maxPerMethod) break;
                    Integer value = intConstant(insn);
                    if (value != null && RANDOM.nextInt(100) < probability) {
                        method.instructions.insertBefore(insn, decryptInt(classNode.name, intDecryptName, classHash, value));
                        method.instructions.remove(insn);
                        changed++;
                        changedInMethod++;
                    } else {
                        Long longValue = longConstant(insn);
                        if (longValue != null && RANDOM.nextInt(100) < probability) {
                            method.instructions.insertBefore(insn, decryptLong(classNode.name, longDecryptName, classHash, longValue));
                            method.instructions.remove(insn);
                            changed++;
                            changedInMethod++;
                        }
                    }
                    insn = next;
                }
            }

            int materializedFields = materializeNumericConstantFields(classNode, intDecryptName, longDecryptName, classHash);
            changed += materializedFields;
            if (changed > 0) {
                classNode.methods.add(buildIntDecryptor(classNode.name, intDecryptName, classSeed));
                classNode.methods.add(buildLongDecryptor(classNode.name, longDecryptName, classSeed));
                pool.markDirty(classNode.name);
                log("Obfuscated {} numeric constants in {} (materialized fields: {})",
                        changed, classNode.name, materializedFields);
            }
        }
    }

    private int materializeNumericConstantFields(ClassNode classNode, String intDecryptName,
                                                String longDecryptName, int classHash) {
        int changed = 0;
        InsnList assignments = new InsnList();

        for (FieldNode field : classNode.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0 || field.value == null) {
                continue;
            }

            if (field.value instanceof Integer value && isIntLikeField(field.desc)) {
                field.value = null;
                assignments.add(decryptInt(classNode.name, intDecryptName, classHash, value));
                assignments.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, field.name, field.desc));
                changed++;
            } else if (field.value instanceof Long value && field.desc.equals("J")) {
                field.value = null;
                assignments.add(decryptLong(classNode.name, longDecryptName, classHash, value));
                assignments.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, field.name, field.desc));
                changed++;
            } else if (field.value instanceof Float value && field.desc.equals("F")) {
                field.value = null;
                assignments.add(decryptInt(classNode.name, intDecryptName, classHash, Float.floatToRawIntBits(value)));
                assignments.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat",
                        "(I)F", false));
                assignments.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, field.name, field.desc));
                changed++;
            } else if (field.value instanceof Double value && field.desc.equals("D")) {
                field.value = null;
                assignments.add(decryptLong(classNode.name, longDecryptName, classHash, Double.doubleToRawLongBits(value)));
                assignments.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble",
                        "(J)D", false));
                assignments.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, field.name, field.desc));
                changed++;
            }
        }

        if (changed > 0) {
            insertIntoClinit(classNode, assignments);
        }
        return changed;
    }

    private boolean isIntLikeField(String desc) {
        return desc.equals("I") || desc.equals("Z") || desc.equals("B")
                || desc.equals("C") || desc.equals("S");
    }

    private void insertIntoClinit(ClassNode classNode, InsnList instructions) {
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

        AbstractInsnNode returnInsn = clinit.instructions.getLast();
        while (returnInsn != null && returnInsn.getOpcode() != Opcodes.RETURN) {
            returnInsn = returnInsn.getPrevious();
        }
        if (returnInsn != null) {
            clinit.instructions.insertBefore(returnInsn, instructions);
        } else {
            clinit.instructions.add(instructions);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        }
    }

    private Integer intConstant(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return op == Opcodes.ICONST_M1 ? -1 : op - Opcodes.ICONST_0;
        }
        if (insn instanceof IntInsnNode intInsn
                && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
            return intInsn.operand;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) {
            return i;
        }
        return null;
    }

    private Long longConstant(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == Opcodes.LCONST_0) return 0L;
        if (op == Opcodes.LCONST_1) return 1L;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long l) {
            return l;
        }
        return null;
    }

    private InsnList decryptInt(String owner, String decryptName, int classHash, int value) {
        int salt = RANDOM.nextInt();
        int encrypted = value ^ mix(classHash ^ salt);
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(encrypted));
        list.add(new LdcInsnNode(salt));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, decryptName, "(II)I", false));
        return list;
    }

    private InsnList decryptLong(String owner, String decryptName, int classHash, long value) {
        int salt = RANDOM.nextInt();
        long key = (((long) mix(classHash ^ salt)) << 32) ^ (mix(classHash ^ salt ^ 0x5bd1e995) & 0xffffffffL);
        long encrypted = value ^ key;
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(encrypted));
        list.add(new LdcInsnNode(salt));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, decryptName, "(JI)J", false));
        return list;
    }

    private MethodNode buildIntDecryptor(String owner, String name, int classSeed) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "(II)I", null, null);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(Opcodes.ILOAD, 0));
        il.add(runtimeKey(owner, classSeed, 1));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 4;
        mn.maxLocals = 2;
        return mn;
    }

    private MethodNode buildLongDecryptor(String owner, String name, int classSeed) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "(JI)J", null, null);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(Opcodes.LLOAD, 0));
        il.add(runtimeKey(owner, classSeed, 2));
        il.add(new InsnNode(Opcodes.I2L));
        il.add(new LdcInsnNode(32));
        il.add(new InsnNode(Opcodes.LSHL));
        il.add(runtimeKey(owner, classSeed, 3));
        il.add(new InsnNode(Opcodes.I2L));
        il.add(new LdcInsnNode(0xffffffffL));
        il.add(new InsnNode(Opcodes.LAND));
        il.add(new InsnNode(Opcodes.LXOR));
        il.add(new InsnNode(Opcodes.LXOR));
        il.add(new InsnNode(Opcodes.LRETURN));
        mn.maxStack = 6;
        mn.maxLocals = 3;
        return mn;
    }

    private InsnList runtimeKey(String owner, int classSeed, int variant) {
        InsnList il = new InsnList();
        il.add(new LdcInsnNode(Type.getObjectType(owner)));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        il.add(new LdcInsnNode(classSeed));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new VarInsnNode(Opcodes.ILOAD, variant == 1 ? 1 : 2));
        if (variant == 3) {
            il.add(new LdcInsnNode(0x5bd1e995));
            il.add(new InsnNode(Opcodes.IXOR));
        }
        il.add(new InsnNode(Opcodes.IXOR));
        addMix(il);
        return il;
    }

    private void addMix(InsnList il) {
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        il.add(new InsnNode(Opcodes.IUSHR));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new LdcInsnNode(0x7feb352d));
        il.add(new InsnNode(Opcodes.IMUL));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        il.add(new InsnNode(Opcodes.IUSHR));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new LdcInsnNode(0x846ca68b));
        il.add(new InsnNode(Opcodes.IMUL));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        il.add(new InsnNode(Opcodes.IUSHR));
        il.add(new InsnNode(Opcodes.IXOR));
    }

    private int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return value;
    }

    private String uniqueMethodName(ClassNode classNode) {
        Set<String> used = new HashSet<>();
        for (MethodNode method : classNode.methods) used.add(method.name);
        String name;
        do {
            name = randomIdentifier();
        } while (used.contains(name));
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
