package dev.frost.obfuscator.transformer.virtualization;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.virtualisation.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class VirtualizationTransformer extends Transformer {

    @Override
    public String getName() {
        return "virtualization";
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
    public Priority priority() {
        return Priority.POST_REMAP;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
    }

    @Override
    public void transform(Context context) {
        TransformerConfig config = context.config();
        ClassPool pool = context.pool();

        VirtualizationOptions options = VirtualizationOptions.from(config);
        Random random = options.seed() > 0 ? new Random(options.seed()) : new Random();
        OpcodeTable opcodeTable = new OpcodeTable(random);

        List<ClassNode> classes = new ArrayList<>(pool.getClasses());
        int virtualizedCount = 0;
        int skippedUnsupported = 0;

        for (ClassNode cn : classes) {
            if (isExcluded(cn.name, config, pool.getGlobalExclusions())) {
                continue;
            }

            int methodIndex = 0;
            List<MethodNode> methods = new ArrayList<>(cn.methods);
            for (MethodNode mn : methods) {
                if (!VirtualizationEligibility.isEligible(cn, mn, options)) {
                    skippedUnsupported++;
                    continue;
                }

                if (random.nextInt(100) >= options.probability()) {
                    continue;
                }

                try {
                    BytecodeTranslator translator = new BytecodeTranslator(mn, opcodeTable);
                    BytecodeTranslator.TranslatedMethod tm = translator.translate();

                    String bytecodeFieldName = "$frost$vm$bytecode_" + methodIndex;
                    String constPoolFieldName = "$frost$vm$constpool_" + methodIndex;

                    cn.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                        bytecodeFieldName,
                        "[B",
                        null,
                        null
                    ));

                    cn.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                        constPoolFieldName,
                        "[Ljava/lang/Object;",
                        null,
                        null
                    ));

                    injectClinitFieldInit(cn, bytecodeFieldName, constPoolFieldName, tm, options, random);

                    rebuildAsStub(cn, mn, bytecodeFieldName, constPoolFieldName, tm);

                    virtualizedCount++;
                    methodIndex++;
                    pool.markDirty(cn.name);
                } catch (Exception e) {
                    log("Failed to virtualize method {} in class {}: {}", mn.name, cn.name, e.getMessage());
                }
            }
        }

        if (virtualizedCount > 0) {
            StringBuilder sb = new StringBuilder();
            int[] table = opcodeTable.getDecodingTable();
            for (int i = 0; i < 256; i++) {
                sb.append(table[i]);
                if (i < 255) sb.append(",");
            }
            String opTableStrValue = sb.toString();

            try {
                ClassNode vmNode = patchClass("/dev/frost/loader/FrostVM.class", Map.of("OP_TABLE_PLACEHOLDER", opTableStrValue));
                pool.addClass(vmNode.name, vmNode);
                pool.markDirty(vmNode.name);

                String[] nested = {
                    "FrostVM$ClassRef",
                    "FrostVM$FieldRef",
                    "FrostVM$MethodRef",
                    "FrostVM$NewPlaceholder"
                };
                for (String className : nested) {
                    ClassNode nestedNode = loadClassNode("/dev/frost/loader/" + className + ".class");
                    pool.addClass(nestedNode.name, nestedNode);
                    pool.markDirty(nestedNode.name);
                }

                log("Injected FrostVM interpreter runtime.");
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject FrostVM runtime interpreter", e);
            }
        }

        context.stats().add("virtualizedMethods", virtualizedCount);
        context.stats().add("virtualizationSkippedUnsupported", skippedUnsupported);
        log("Virtualized {} methods ({} unsupported/skipped by safety gate).", virtualizedCount, skippedUnsupported);
    }

    private void injectClinitFieldInit(ClassNode cn, String bytecodeFieldName, String constPoolFieldName, BytecodeTranslator.TranslatedMethod tm, VirtualizationOptions options, Random random) {
        MethodNode clinit = null;
        for (MethodNode methodNode : cn.methods) {
            if (methodNode.name.equals("<clinit>")) {
                clinit = methodNode;
                break;
            }
        }
        if (clinit == null) {
            clinit = new MethodNode(
                Opcodes.ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null
            );
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(clinit);
        }

        InsnList list = new InsnList();

        int bytecodeKey = options.encryptBytecode() ? random.nextInt() : 0;
        byte[] storedBytecode = options.encryptBytecode() ? encodeBytecode(tm.bytecode, bytecodeKey) : tm.bytecode;
        String base64Bytes = Base64.getEncoder().encodeToString(storedBytecode);
        list.add(new LdcInsnNode(base64Bytes));
        if (options.encryptBytecode()) {
            list.add(getIntInsn(bytecodeKey));
            list.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/frost/loader/FrostVM",
                "decodeBytecode",
                "(Ljava/lang/String;I)[B",
                false
            ));
        } else {
            list.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Base64",
                "getDecoder",
                "()Ljava/util/Base64$Decoder;",
                false
            ));
            list.add(new InsnNode(Opcodes.SWAP));
            list.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Base64$Decoder",
                "decode",
                "(Ljava/lang/String;)[B",
                false
            ));
        }
        list.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, bytecodeFieldName, "[B"));

        list.add(getIntInsn(tm.constPool.length));
        list.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));

        for (int i = 0; i < tm.constPool.length; i++) {
            list.add(new InsnNode(Opcodes.DUP));
            list.add(getIntInsn(i));

            Object val = tm.constPool[i];
            if (val == null) {
                list.add(new InsnNode(Opcodes.ACONST_NULL));
            } else if (val instanceof Integer) {
                list.add(getIntInsn((Integer) val));
                boxPrimitive(list, Type.INT_TYPE);
            } else if (val instanceof Long) {
                list.add(new LdcInsnNode(val));
                boxPrimitive(list, Type.LONG_TYPE);
            } else if (val instanceof Float) {
                list.add(new LdcInsnNode(val));
                boxPrimitive(list, Type.FLOAT_TYPE);
            } else if (val instanceof Double) {
                list.add(new LdcInsnNode(val));
                boxPrimitive(list, Type.DOUBLE_TYPE);
            } else if (val instanceof String) {
                list.add(new LdcInsnNode(val));
            } else if (val instanceof VirtualConstant.ClassRef ref) {
                list.add(new TypeInsnNode(Opcodes.NEW, "dev/frost/loader/FrostVM$ClassRef"));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new LdcInsnNode(ref.name()));
                list.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "dev/frost/loader/FrostVM$ClassRef",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false
                ));
            } else if (val instanceof VirtualConstant.FieldRef ref) {
                list.add(new TypeInsnNode(Opcodes.NEW, "dev/frost/loader/FrostVM$FieldRef"));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new LdcInsnNode(ref.className()));
                list.add(new LdcInsnNode(ref.name()));
                list.add(new LdcInsnNode(ref.desc()));
                list.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "dev/frost/loader/FrostVM$FieldRef",
                    "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false
                ));
            } else if (val instanceof VirtualConstant.MethodRef ref) {
                list.add(new TypeInsnNode(Opcodes.NEW, "dev/frost/loader/FrostVM$MethodRef"));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new LdcInsnNode(ref.className()));
                list.add(new LdcInsnNode(ref.name()));
                list.add(new LdcInsnNode(ref.desc()));
                list.add(getIntInsn(ref.isInterface() ? 1 : 0));
                list.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "dev/frost/loader/FrostVM$MethodRef",
                    "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V",
                    false
                ));
            } else {
                throw new IllegalArgumentException("Unknown constant pool object type: " + val.getClass().getName());
            }

            list.add(new InsnNode(Opcodes.AASTORE));
        }

        list.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, constPoolFieldName, "[Ljava/lang/Object;"));

        clinit.instructions.insert(list);
    }

    private void rebuildAsStub(ClassNode cn, MethodNode mn, String bytecodeFieldName, String constPoolFieldName, BytecodeTranslator.TranslatedMethod tm) {
        mn.instructions.clear();
        if (mn.tryCatchBlocks != null) {
            mn.tryCatchBlocks.clear();
        }
        if (mn.localVariables != null) {
            mn.localVariables.clear();
        }

        InsnList list = new InsnList();

        list.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, bytecodeFieldName, "[B"));

        list.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, constPoolFieldName, "[Ljava/lang/Object;"));

        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        int argsCount = argTypes.length + (isStatic ? 0 : 1);

        list.add(getIntInsn(argsCount));
        list.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));

        int argIndex = 0;
        if (!isStatic) {
            list.add(new InsnNode(Opcodes.DUP));
            list.add(getIntInsn(0));
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new InsnNode(Opcodes.AASTORE));
            argIndex = 1;
        }

        int localOffset = isStatic ? 0 : 1;
        for (int i = 0; i < argTypes.length; i++) {
            Type type = argTypes[i];
            list.add(new InsnNode(Opcodes.DUP));
            list.add(getIntInsn(argIndex++));

            int loadOpcode = type.getOpcode(Opcodes.ILOAD);
            list.add(new VarInsnNode(loadOpcode, localOffset));
            localOffset += type.getSize();

            boxPrimitive(list, type);
            list.add(new InsnNode(Opcodes.AASTORE));
        }

        list.add(getIntInsn(tm.argSlots.length));
        list.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        for (int i = 0; i < tm.argSlots.length; i++) {
            list.add(new InsnNode(Opcodes.DUP));
            list.add(getIntInsn(i));
            list.add(getIntInsn(tm.argSlots[i]));
            list.add(new InsnNode(Opcodes.IASTORE));
        }

        list.add(getIntInsn(tm.maxLocals));
        list.add(getIntInsn(tm.maxStack));

        list.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "dev/frost/loader/FrostVM",
            "execute",
            "([B[Ljava/lang/Object;[Ljava/lang/Object;[III)Ljava/lang/Object;",
            false
        ));

        Type retType = Type.getReturnType(mn.desc);
        if (retType.getSort() == Type.VOID) {
            list.add(new InsnNode(Opcodes.POP));
            list.add(new InsnNode(Opcodes.RETURN));
        } else {
            unboxPrimitiveOrCast(list, retType);
            list.add(new InsnNode(retType.getOpcode(Opcodes.IRETURN)));
        }

        mn.instructions.add(list);
    }

    private AbstractInsnNode getIntInsn(int val) {
        if (val >= -1 && val <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + val);
        } else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, val);
        } else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, val);
        } else {
            return new LdcInsnNode(val);
        }
    }

    private void boxPrimitive(InsnList list, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
                break;
            case Type.BYTE:
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
                break;
            case Type.CHAR:
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
                break;
            case Type.SHORT:
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
                break;
            case Type.INT:
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                break;
            case Type.LONG:
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
                break;
            case Type.FLOAT:
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
                break;
            case Type.DOUBLE:
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
                break;
        }
    }

    private void unboxPrimitiveOrCast(InsnList list, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                break;
            case Type.BYTE:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false));
                break;
            case Type.CHAR:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
                break;
            case Type.SHORT:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false));
                break;
            case Type.INT:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
                break;
            case Type.LONG:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false));
                break;
            case Type.FLOAT:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
                break;
            case Type.DOUBLE:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
                break;
        }
    }

    private byte[] encodeBytecode(byte[] bytecode, int key) {
        byte[] encoded = Arrays.copyOf(bytecode, bytecode.length);
        int state = key ^ 0x6d2b79f5;
        for (int i = 0; i < encoded.length; i++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            encoded[i] = (byte) (encoded[i] ^ state);
        }
        return encoded;
    }

    private ClassNode patchClass(String resourcePath, Map<String, String> replacements) throws IOException {
        try (InputStream is = VirtualizationTransformer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Failed to load pre-compiled runtime class: " + resourcePath);
            }
            ClassReader reader = new ClassReader(is);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            for (MethodNode mn : node.methods) {
                if (mn.instructions != null) {
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn instanceof LdcInsnNode ldc) {
                            if (ldc.cst instanceof String s) {
                                if (replacements.containsKey(s)) {
                                    ldc.cst = replacements.get(s);
                                }
                            }
                        }
                    }
                }
            }
            return node;
        }
    }

    private ClassNode loadClassNode(String resourcePath) throws IOException {
        try (InputStream is = VirtualizationTransformer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Failed to load pre-compiled runtime class: " + resourcePath);
            }
            ClassReader reader = new ClassReader(is);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            return node;
        }
    }

    private int getIntOption(TransformerConfig config, String key, int defaultValue) {
        Object val = config.getOptions().get(key);
        if (val instanceof Number n) return n.intValue();
        return val != null ? Integer.parseInt(val.toString()) : defaultValue;
    }

    private boolean getBooleanOption(TransformerConfig config, String key, boolean defaultValue) {
        Object val = config.getOptions().get(key);
        if (val instanceof Boolean b) return b;
        return val != null ? Boolean.parseBoolean(val.toString()) : defaultValue;
    }
}
