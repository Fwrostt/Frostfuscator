package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class AntiDecompilerTransformer extends Transformer {

    @Override
    public String getName() {
        return "anti-decompiler";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public void transform(Context context) {
        int changed = 0;
        for (ClassNode classNode : context.pool().getClasses()) {
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())) {
                continue;
            }
            if ((classNode.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) {
                continue;
            }
            String fieldName = "__frost$cfr$fernflower$procyon$jadx";
            String trapName = "__frost$decompiler$trap";
            boolean touched = false;
            if (classNode.fields.stream().noneMatch(field -> fieldName.equals(field.name))) {
                classNode.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        fieldName,
                        "I",
                        null,
                        0
                ));
                touched = true;
            }
            if (classNode.methods.stream().noneMatch(method -> trapName.equals(method.name) && "(I)I".equals(method.desc))) {
                classNode.methods.add(buildTrap(trapName));
                touched = true;
            }
            int guarded = injectTrapCalls(classNode, trapName);
            if (classNode.sourceFile == null) {
                classNode.sourceFile = "FrostProtected.java";
            }
            if (touched || guarded > 0) {
                context.pool().markDirty(classNode.name);
                changed++;
            }
        }
        context.stats().add("antiDecompilerClasses", changed);
        log("Added anti-decompiler bytecode to {} classes", changed);
    }

    private int injectTrapCalls(ClassNode classNode, String trapName) {
        int changed = 0;
        for (MethodNode method : classNode.methods) {
            if (!shouldInject(method, trapName)) {
                continue;
            }
            InsnList insns = new InsnList();
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, trapName, "(I)I", false));
            insns.add(new InsnNode(Opcodes.POP));
            method.instructions.insert(insns);
            changed++;
        }
        return changed;
    }

    private boolean shouldInject(MethodNode method, String trapName) {
        if (trapName.equals(method.name)
                || "<init>".equals(method.name)
                || "<clinit>".equals(method.name)
                || method.instructions == null
                || method.instructions.size() == 0) {
            return false;
        }
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    private MethodNode buildTrap(String name) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "(I)I",
                null,
                null
        );
        LabelNode start = new LabelNode();
        LabelNode afterThrowCheck = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();

        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));

        InsnList insns = method.instructions;
        insns.add(start);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new LdcInsnNode(Integer.MIN_VALUE));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, afterThrowCheck));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));
        insns.add(afterThrowCheck);
        insns.add(new IincInsnNode(0, 1));
        insns.add(end);
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 0));
        insns.add(done);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new InsnNode(Opcodes.IRETURN));

        method.maxStack = 3;
        method.maxLocals = 2;
        return method;
    }
}
