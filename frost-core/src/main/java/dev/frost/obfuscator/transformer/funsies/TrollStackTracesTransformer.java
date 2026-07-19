package dev.frost.obfuscator.transformer.funsies;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Replaces explicit Throwable#printStackTrace calls with a verifier-safe banner.
 * The original exception remains available to the application; only printing changes.
 */
public final class TrollStackTracesTransformer extends Transformer {
    private static final String HELPER = "__frost$troll$trace";

    @Override
    public String getName() {
        return "troll-stack-traces";
    }

    @Override
    public String getCategory() {
        return "Funsies";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        String message = context.config().getOption("message",
                "  /\\_/\\\\\n ( o.o )  Frost says: stack trace unavailable\n  > ^ <");
        int calls = 0;
        for (ClassNode node : context.pool().getClasses()) {
            if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0
                    || !shouldProcess(node.name, context.config(), context.pool().getGlobalExclusions(),
                    context.pool().getGlobalInclusions())) {
                continue;
            }
            String helperName = helperName(node);
            Set<String> descriptors = new HashSet<>();
            for (MethodNode method : node.methods) {
                if (method.instructions == null || method.name.equals(helperName)) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                            || !call.name.equals("printStackTrace")
                            || !isThrowableOwner(context, call.owner)
                            || !isSupported(call.desc)) {
                        continue;
                    }
                    String helperDesc = helperDescriptor(call.desc);
                    method.instructions.set(call,
                            new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, helperName, helperDesc, false));
                    descriptors.add(helperDesc);
                    calls++;
                }
            }
            if (!descriptors.isEmpty()) {
                for (String descriptor : descriptors) {
                    if (node.methods.stream().noneMatch(m -> m.name.equals(helperName) && m.desc.equals(descriptor))) {
                        node.methods.add(buildHelper(helperName, descriptor, message));
                    }
                }
                context.pool().markDirty(node.name);
            }
        }
        context.stats().add("trolledStackTraceCalls", calls);
        log("Rewrote {} explicit printStackTrace calls", calls);
    }

    private boolean isSupported(String desc) {
        return desc.equals("()V")
                || desc.equals("(Ljava/io/PrintStream;)V")
                || desc.equals("(Ljava/io/PrintWriter;)V");
    }

    private boolean isThrowableOwner(Context context, String owner) {
        if (owner.equals("java/lang/Throwable")) return true;
        String hierarchyName = owner;
        for (var mapping : context.mappings().getClassMappings().entrySet()) {
            if (mapping.getValue().equals(owner)) {
                hierarchyName = mapping.getKey();
                break;
            }
        }
        return context.pool().getHierarchy().getAllParents(hierarchyName).contains("java/lang/Throwable");
    }

    private String helperDescriptor(String original) {
        return "(Ljava/lang/Throwable;" + original.substring(1);
    }

    private String helperName(ClassNode node) {
        String candidate = HELPER;
        int suffix = 0;
        while (hasMethodNamed(node, candidate)) candidate = HELPER + "$" + ++suffix;
        return candidate;
    }

    private boolean hasMethodNamed(ClassNode node, String name) {
        return node.methods.stream().anyMatch(method -> method.name.equals(name));
    }

    private MethodNode buildHelper(String name, String desc, String message) {
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        if (desc.equals("(Ljava/lang/Throwable;)V")) {
            helper.instructions.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
            helper.instructions.add(new LdcInsnNode(message));
            helper.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        } else {
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            helper.instructions.add(new LdcInsnNode(message));
            String owner = desc.contains("PrintWriter") ? "java/io/PrintWriter" : "java/io/PrintStream";
            helper.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, owner, "println", "(Ljava/lang/String;)V", false));
        }
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        helper.maxStack = 2;
        helper.maxLocals = desc.equals("(Ljava/lang/Throwable;)V") ? 1 : 2;
        return helper;
    }
}
