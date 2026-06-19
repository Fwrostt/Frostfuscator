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

public class FlowExceptionTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "flow-exception";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String strength = config.getOption("strength", "GOOD").toUpperCase();
        int passes = strength.equals("AGGRESSIVE") ? 2 : 1;
        int probability = strength.equals("WEAK") ? 35 : 70;

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            String trapField = ensureTrapField(classNode);
            int changed = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if (AccessHelper.isInitializer(method)) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                for (int i = 0; i < passes; i++) {
                    if (RANDOM.nextInt(100) < probability && insertGuard(classNode.name, trapField, method)) {
                        changed++;
                    }
                }
            }

            if (changed > 0) {
                pool.markDirty(classNode.name);
                log("Inserted {} exception-driven guards in {}", changed, classNode.name);
            }
        }
    }

    private boolean insertGuard(String owner, String trapField, MethodNode method) {
        AbstractInsnNode anchor = firstExecutable(method);
        if (anchor == null) return false;

        LabelNode start = new LabelNode(new Label());
        LabelNode end = new LabelNode(new Label());
        LabelNode handler = new LabelNode(new Label());
        LabelNode join = new LabelNode(new Label());

        InsnList list = new InsnList();
        list.add(start);
        list.add(zeroPredicate(owner, trapField));
        list.add(new JumpInsnNode(Opcodes.IFEQ, join));
        list.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false));
        list.add(new InsnNode(Opcodes.ATHROW));
        list.add(end);
        list.add(new JumpInsnNode(Opcodes.GOTO, join));
        list.add(handler);
        list.add(new InsnNode(Opcodes.POP));
        list.add(join);

        method.instructions.insertBefore(anchor, list);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));
        return true;
    }

    private InsnList zeroPredicate(String owner, String trapField) {
        InsnList list = new InsnList();
        list.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, trapField, "I"));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        list.add(new InsnNode(Opcodes.L2I));
        list.add(new InsnNode(Opcodes.IXOR));
        switch (RANDOM.nextInt(3)) {
            case 0 -> {
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(Opcodes.IXOR));
            }
            case 1 -> {
                list.add(new InsnNode(Opcodes.ICONST_1));
                list.add(new InsnNode(Opcodes.ISHL));
                list.add(new InsnNode(Opcodes.ICONST_1));
                list.add(new InsnNode(Opcodes.IAND));
            }
            default -> {
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "bitCount", "(I)I", false));
                list.add(new IntInsnNode(Opcodes.BIPUSH, 32));
                list.add(new InsnNode(Opcodes.IAND));
            }
        }
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
        init.add(new LdcInsnNode(RANDOM.nextInt()));
        init.add(new InsnNode(Opcodes.IXOR));
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
}
