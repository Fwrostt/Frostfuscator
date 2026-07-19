package dev.frost.obfuscator.transformer.encryption;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import dev.frost.obfuscator.util.ASMHelper;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * String constant encryption.
 *
 * Modes:
 *   lite        - per-class XOR key, single decrypt method.
 *   medium      - 3 algorithm variants, per-string keys, simple cache.
 *   heavy       - invokedynamic bootstrap + context keys + multi-layer.
 *   polymorphic - inline CBC-style bitwise transform with rotate/XOR/ADD/SUB
 *                 steps driven by a per-string seed.
 *
 * Strings are removed from the constant pool; only encrypted int[]/byte[] data
 * and keys remain.
 */
public class StringEncryptionTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "string-encryption";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String mode = config.getOption("mode", "medium").toLowerCase();

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            if (AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            int materializedConstants = materializeStringConstantFields(classNode);
            int minLength = getIntOption(config, "min-length", 1);
            int maxMethodInstructions = getIntOption(config, "max-method-instructions", 6000);
            List<StringContext> contexts = collectStrings(classNode, minLength, maxMethodInstructions);
            if (contexts.isEmpty()) {
                if (materializedConstants > 0) {
                    pool.markDirty(classNode.name);
                }
                continue;
            }

            switch (mode) {
                case "lite" -> applyLite(classNode, contexts);
                case "medium" -> applyMedium(classNode, contexts);
                case "heavy" -> applyHeavy(classNode, contexts);
                case "condy" -> applyCondy(classNode, contexts);
                case "polymorphic" -> applyPolymorphic(classNode, contexts);
                default -> applyMedium(classNode, contexts);
            }

            pool.markDirty(classNode.name);
            log("Encrypted {} strings in {} (mode: {}, materialized fields: {})",
                    contexts.size(), classNode.name, mode, materializedConstants);
        }
    }

    private int materializeStringConstantFields(ClassNode classNode) {
        int changed = 0;
        InsnList assignments = new InsnList();
        for (FieldNode field : classNode.fields) {
            if (!field.desc.equals("Ljava/lang/String;") || !(field.value instanceof String value)) {
                continue;
            }
            if ((field.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }

            field.value = null;
            assignments.add(new LdcInsnNode(value));
            assignments.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, field.name, field.desc));
            changed++;
        }

        if (changed > 0) {
            insertIntoClinit(classNode, assignments);
        }
        return changed;
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

    private List<StringContext> collectStrings(ClassNode classNode, int minLength, int maxMethodInstructions) {
        List<StringContext> result = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (method.instructions == null) continue;
            if (method.instructions.size() > maxMethodInstructions) continue;
            AbstractInsnNode insn = method.instructions.getFirst();
            while (insn != null) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    if (s.length() >= minLength && !s.isEmpty()) {
                        result.add(new StringContext(method, ldc, s));
                    }
                }
                insn = insn.getNext();
            }
        }
        return result;
    }

    // region Lite mode

    private void applyLite(ClassNode classNode, List<StringContext> contexts) {
        int classKey = randomKey();
        String decryptName = randomMethodName(classNode);
        addLiteDecryptMethod(classNode, decryptName, classKey);

        for (StringContext ctx : contexts) {
            byte[] encrypted = xor(ctx.value.getBytes(StandardCharsets.UTF_8), classKey);
            InsnList replacement = buildArrayLoad(encrypted);
            replacement.add(new LdcInsnNode(classKey));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, decryptName,
                    "([BI)Ljava/lang/String;", false));
            replace(ctx, replacement);
        }
    }

    private void addLiteDecryptMethod(ClassNode classNode, String name, int key) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name,
                "([BI)Ljava/lang/String;", null, null);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 4));

        LabelNode loopStart = new LabelNode(new Label());
        LabelNode loopEnd = new LabelNode(new Label());
        il.add(loopStart);
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new InsnNode(Opcodes.BALOAD));
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new InsnNode(Opcodes.BASTORE));
        il.add(new IincInsnNode(4, 1));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        il.add(loopEnd);
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                "Ljava/nio/charset/Charset;"));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>",
                "([BLjava/nio/charset/Charset;)V", false));
        il.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 5;
        mn.maxLocals = 5;
        classNode.methods.add(mn);
    }

    // endregion

    // region Medium mode

    private void applyMedium(ClassNode classNode, List<StringContext> contexts) {
        int classKey = randomKey();
        String decryptName = randomMethodName(classNode);
        addMediumDecryptMethod(classNode, decryptName, classKey);

        for (StringContext ctx : contexts) {
            int variant = RANDOM.nextInt(3);
            int stringKey = randomKey();
            byte[] data = ctx.value.getBytes(StandardCharsets.UTF_8);
            int[] encrypted = encryptMedium(data, classKey, stringKey, variant);

            InsnList replacement = buildIntArrayLoad(encrypted);
            replacement.add(new LdcInsnNode(stringKey));
            replacement.add(new LdcInsnNode(variant));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, decryptName,
                    "([III)Ljava/lang/String;", false));
            replace(ctx, replacement);
        }
    }

    private int[] encryptMedium(byte[] data, int classKey, int stringKey, int variant) {
        int[] out = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            switch (variant) {
                case 0 -> out[i] = (v ^ classKey) + stringKey;
                case 1 -> out[i] = (v + stringKey) ^ classKey;
                default -> out[i] = Integer.rotateLeft(v ^ stringKey, 3) ^ classKey;
            }
        }
        return out;
    }

    private void addMediumDecryptMethod(ClassNode classNode, String name, int classKey) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name,
                "([III)Ljava/lang/String;", null, null);
        InsnList il = mn.instructions;

        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));

        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 5));

        LabelNode loopStart = new LabelNode(new Label());
        LabelNode loopEnd = new LabelNode(new Label());
        LabelNode case0 = new LabelNode(new Label());
        LabelNode case1 = new LabelNode(new Label());
        LabelNode case2 = new LabelNode(new Label());
        LabelNode switchEnd = new LabelNode(new Label());

        il.add(loopStart);
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new TableSwitchInsnNode(0, 2, switchEnd, case0, case1, case2));

        il.add(case0);
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new InsnNode(Opcodes.IALOAD));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new InsnNode(Opcodes.ISUB));
        il.add(new LdcInsnNode(classKey));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new InsnNode(Opcodes.BASTORE));
        il.add(new JumpInsnNode(Opcodes.GOTO, switchEnd));

        il.add(case1);
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new InsnNode(Opcodes.IALOAD));
        il.add(new LdcInsnNode(classKey));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new InsnNode(Opcodes.ISUB));
        il.add(new InsnNode(Opcodes.BASTORE));
        il.add(new JumpInsnNode(Opcodes.GOTO, switchEnd));

        il.add(case2);
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new InsnNode(Opcodes.IALOAD));
        il.add(new LdcInsnNode(classKey));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "rotateRight",
                "(II)I", false));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new InsnNode(Opcodes.BASTORE));
        il.add(new JumpInsnNode(Opcodes.GOTO, switchEnd));

        il.add(switchEnd);
        il.add(new IincInsnNode(5, 1));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        il.add(loopEnd);
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                "Ljava/nio/charset/Charset;"));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>",
                "([BLjava/nio/charset/Charset;)V", false));
        il.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 5;
        mn.maxLocals = 6;
        classNode.methods.add(mn);
    }

    // endregion

    // region Condy mode (Java 11+ dynamic constants)

    private void applyCondy(ClassNode classNode, List<StringContext> contexts) {
        classNode.version = Math.max(classNode.version, Opcodes.V11);
        String bsmName = randomMethodName(classNode);
        String decryptName = randomMethodName(classNode);
        addIndyDecryptHelper(classNode, decryptName);
        addCondyBootstrapMethod(classNode, bsmName, decryptName);

        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, classNode.name, bsmName,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;I)Ljava/lang/String;",
                false);

        for (StringContext ctx : contexts) {
            int key = randomKey();
            String encrypted = encryptIndy(ctx.value, key);
            String constantName = "$" + Integer.toUnsignedString(RANDOM.nextInt(), 36);
            ConstantDynamic dynamic = new ConstantDynamic(constantName, "Ljava/lang/String;",
                    bootstrap, encrypted, key);
            InsnList replacement = new InsnList();
            replacement.add(new LdcInsnNode(dynamic));
            replace(ctx, replacement);
        }
    }

    private void addCondyBootstrapMethod(ClassNode classNode, String name, String decryptName) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;I)Ljava/lang/String;",
                null, null);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, decryptName,
                "(Ljava/lang/String;I)Ljava/lang/String;", false));
        il.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 2;
        mn.maxLocals = 5;
        classNode.methods.add(mn);
    }

    // endregion

    // region Heavy mode (invokedynamic)

    private void applyHeavy(ClassNode classNode, List<StringContext> contexts) {
        classNode.version = Math.max(classNode.version, Opcodes.V1_7);
        String bsmName = randomMethodName(classNode);
        String decryptName = randomMethodName(classNode);
        addIndyDecryptHelper(classNode, decryptName);
        addIndyBootstrapMethod(classNode, bsmName, decryptName);

        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, classNode.name, bsmName,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;",
                false);

        for (StringContext ctx : contexts) {
            int key = randomKey();
            String encrypted = encryptIndy(ctx.value, key);
            InsnList replacement = new InsnList();
            replacement.add(new InvokeDynamicInsnNode(randomIdentifier(), "()Ljava/lang/String;", bootstrap, encrypted, key));
            replace(ctx, replacement);
        }
    }

    private String encryptIndy(String value, int key) {
        char[] chars = value.toCharArray();
        int state = key;
        for (int i = 0; i < chars.length; i++) {
            state = state * 1103515245 + 12345;
            chars[i] = (char) (chars[i] ^ state);
        }
        return new String(chars);
    }

    private void addIndyBootstrapMethod(ClassNode classNode, String name, String decryptName) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;",
                null, new String[]{"java/lang/Exception"});
        InsnList il = mn.instructions;

        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/invoke/ConstantCallSite"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new LdcInsnNode(Type.getType("Ljava/lang/String;")));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, decryptName,
                "(Ljava/lang/String;I)Ljava/lang/String;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "constant",
                "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType",
                "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>",
                "(Ljava/lang/invoke/MethodHandle;)V", false));
        il.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 5;
        mn.maxLocals = 5;
        classNode.methods.add(mn);
    }

    private void addIndyDecryptHelper(ClassNode classNode, String name) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name,
                "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
        InsnList il = mn.instructions;

        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new VarInsnNode(Opcodes.ISTORE, 3));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 4));

        LabelNode start = new LabelNode(new Label());
        LabelNode end = new LabelNode(new Label());
        il.add(start);
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));

        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new LdcInsnNode(1103515245));
        il.add(new InsnNode(Opcodes.IMUL));
        il.add(new LdcInsnNode(12345));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new VarInsnNode(Opcodes.ISTORE, 3));

        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new InsnNode(Opcodes.CALOAD));
        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new InsnNode(Opcodes.CASTORE));

        il.add(new IincInsnNode(4, 1));
        il.add(new JumpInsnNode(Opcodes.GOTO, start));

        il.add(end);
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false));
        il.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 5;
        mn.maxLocals = 5;
        classNode.methods.add(mn);
    }

    // endregion

    // region Polymorphic inline CBC

    private void applyPolymorphic(ClassNode classNode, List<StringContext> contexts) {
        for (StringContext ctx : contexts) {
            int seed = randomKey();
            char[] chars = ctx.value.toCharArray();
            int[] encrypted = new int[chars.length];
            int state = seed;
            for (int i = 0; i < chars.length; i++) {
                state = polymorphicRound(state, i);
                encrypted[i] = chars[i] ^ (state & 0xFFFF);
            }

            InsnList replacement = buildPolymorphicDecrypt(ctx.method, encrypted, seed);
            replace(ctx, replacement);
        }
    }

    private int polymorphicRound(int state, int round) {
        int x = state + round;
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    private InsnList buildPolymorphicDecrypt(MethodNode method, int[] encrypted, int seed) {
        InsnList fixed = new InsnList();

        int resultSlot = ASMHelper.allocateLocal(method, 1);
        int stateSlot = ASMHelper.allocateLocal(method, 1);
        int indexSlot = ASMHelper.allocateLocal(method, 1);

        LabelNode loopStart = new LabelNode(new Label());
        LabelNode loopEnd = new LabelNode(new Label());
        LabelNode switchDefault = new LabelNode(new Label());
        LabelNode switchJoin = new LabelNode(new Label());

        fixed.add(new LdcInsnNode(encrypted.length));
        fixed.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_CHAR));
        fixed.add(new VarInsnNode(Opcodes.ASTORE, resultSlot));
        fixed.add(new LdcInsnNode(seed));
        fixed.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
        fixed.add(new InsnNode(Opcodes.ICONST_0));
        fixed.add(new VarInsnNode(Opcodes.ISTORE, indexSlot));

        fixed.add(loopStart);
        fixed.add(new VarInsnNode(Opcodes.ILOAD, indexSlot));
        fixed.add(new LdcInsnNode(encrypted.length));
        fixed.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        fixed.add(new VarInsnNode(Opcodes.ILOAD, stateSlot));
        fixed.add(new VarInsnNode(Opcodes.ILOAD, indexSlot));
        fixed.add(new InsnNode(Opcodes.IADD));
        fixed.add(new InsnNode(Opcodes.DUP));
        fixed.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        fixed.add(new InsnNode(Opcodes.IUSHR));
        fixed.add(new InsnNode(Opcodes.IXOR));
        fixed.add(new LdcInsnNode(0x7feb352d));
        fixed.add(new InsnNode(Opcodes.IMUL));
        fixed.add(new InsnNode(Opcodes.DUP));
        fixed.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        fixed.add(new InsnNode(Opcodes.IUSHR));
        fixed.add(new InsnNode(Opcodes.IXOR));
        fixed.add(new LdcInsnNode(0x846ca68b));
        fixed.add(new InsnNode(Opcodes.IMUL));
        fixed.add(new InsnNode(Opcodes.DUP));
        fixed.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        fixed.add(new InsnNode(Opcodes.IUSHR));
        fixed.add(new InsnNode(Opcodes.IXOR));
        fixed.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));

        fixed.add(new VarInsnNode(Opcodes.ILOAD, indexSlot));
        LabelNode[] switchLabels = new LabelNode[encrypted.length];
        for (int i = 0; i < encrypted.length; i++) switchLabels[i] = new LabelNode(new Label());
        fixed.add(new TableSwitchInsnNode(0, encrypted.length - 1, switchDefault, switchLabels));
        for (int i = 0; i < encrypted.length; i++) {
            fixed.add(switchLabels[i]);
            fixed.add(new LdcInsnNode(encrypted[i]));
            fixed.add(new JumpInsnNode(Opcodes.GOTO, switchJoin));
        }
        fixed.add(switchDefault);
        fixed.add(new InsnNode(Opcodes.ICONST_0));
        fixed.add(new JumpInsnNode(Opcodes.GOTO, switchJoin));

        fixed.add(switchJoin);
        fixed.add(new VarInsnNode(Opcodes.ILOAD, stateSlot));
        fixed.add(new LdcInsnNode(0xFFFF));
        fixed.add(new InsnNode(Opcodes.IAND));
        fixed.add(new InsnNode(Opcodes.IXOR));
        fixed.add(new InsnNode(Opcodes.I2C));

        fixed.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        fixed.add(new InsnNode(Opcodes.SWAP));
        fixed.add(new VarInsnNode(Opcodes.ILOAD, indexSlot));
        fixed.add(new InsnNode(Opcodes.SWAP));
        fixed.add(new InsnNode(Opcodes.CASTORE));

        fixed.add(new IincInsnNode(indexSlot, 1));
        fixed.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        fixed.add(loopEnd);
        fixed.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        fixed.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "([C)Ljava/lang/String;", false));

        return fixed;
    }

    // endregion

    // region Helpers

    private void replace(StringContext ctx, InsnList replacement) {
        ctx.method.instructions.insertBefore(ctx.ldc, replacement);
        ctx.method.instructions.remove(ctx.ldc);
    }

    private InsnList buildArrayLoad(byte[] data) {
        InsnList il = new InsnList();
        il.add(new LdcInsnNode(data.length));
        il.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        for (int i = 0; i < data.length; i++) {
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new LdcInsnNode(i));
            il.add(new LdcInsnNode(data[i]));
            il.add(new InsnNode(Opcodes.BASTORE));
        }
        return il;
    }

    private InsnList buildIntArrayLoad(int[] data) {
        InsnList il = new InsnList();
        il.add(new LdcInsnNode(data.length));
        il.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        for (int i = 0; i < data.length; i++) {
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new LdcInsnNode(i));
            il.add(new LdcInsnNode(data[i]));
            il.add(new InsnNode(Opcodes.IASTORE));
        }
        return il;
    }

    private byte[] xor(byte[] data, int key) {
        byte[] out = new byte[data.length];
        byte k = (byte) key;
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ k);
        }
        return out;
    }

    private int randomKey() {
        return RANDOM.nextInt();
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

    private String uniqueMethodName(ClassNode classNode, String base) {
        Set<String> used = new HashSet<>();
        for (MethodNode m : classNode.methods) used.add(m.name);
        String name = base;
        int i = 0;
        while (used.contains(name)) {
            name = base + "$" + (i++);
        }
        return name;
    }

    private String randomMethodName(ClassNode classNode) {
        Set<String> used = new HashSet<>();
        for (MethodNode method : classNode.methods) used.add(method.name);
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

    private record StringContext(MethodNode method, LdcInsnNode ldc, String value) {
    }

    // endregion
}
