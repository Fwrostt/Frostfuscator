package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class AntiDebugTransformer extends Transformer {

    @Override
    public String getName() {
        return "anti-debug";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public void transform(Context context) {
        String methodName = context.config().getOption("method-name", "__frost$antiDebug");
        int injected = 0;
        int guardedMethods = 0;

        for (ClassNode classNode : context.pool().getClasses()) {
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())) {
                continue;
            }

            if (hasMethod(classNode, methodName)) {
                continue;
            }

            classNode.methods.add(buildGuard(methodName));
            int classGuards = injectGuards(classNode, methodName);
            if (classGuards == 0) {
                continue;
            }
            context.pool().markDirty(classNode.name);
            injected++;
            guardedMethods += classGuards;
        }

        context.stats().add("antiDebugHooks", injected);
        context.stats().add("antiDebugGuardedMethods", guardedMethods);
        log("Injected {} anti-debug hooks across {} methods", injected, guardedMethods);
    }

    private int injectGuards(ClassNode classNode, String guardName) {
        int injected = 0;
        for (MethodNode method : classNode.methods) {
            if (shouldGuard(method, guardName)) {
                method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, guardName, "()V", false));
                injected++;
            }
        }
        return injected;
    }

    private boolean shouldGuard(MethodNode method, String guardName) {
        if (guardName.equals(method.name)
                || "<init>".equals(method.name)
                || "<clinit>".equals(method.name)
                || method.instructions == null
                || method.instructions.size() == 0) {
            return false;
        }
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    private boolean hasMethod(ClassNode classNode, String name) {
        return classNode.methods.stream().anyMatch(method -> name.equals(method.name) && "()V".equals(method.desc));
    }

    private MethodNode buildGuard(String name) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "()V",
                null,
                null
        );
        LabelNode ok = new LabelNode();
        InsnList insns = method.instructions;
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/management/ManagementFactory", "getRuntimeMXBean", "()Ljava/lang/management/RuntimeMXBean;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/management/RuntimeMXBean", "getInputArguments", "()Ljava/util/List;", true));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false));
        insns.add(new LdcInsnNode("jdwp"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, ok));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode("Debugging is disabled"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));
        insns.add(ok);
        insns.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 3;
        method.maxLocals = 0;
        return method;
    }
}
