package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.Set;

public final class AntiAttachTransformer extends Transformer {
    private static final String RUNTIME_CLASS = "dev/frost/runtime/AntiAttachRuntime";
    private static final String VERIFY_DESC = "(ZZZZLjava/lang/String;)V";

    @Override
    public String getName() {
        return "anti-attach";
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
        boolean requireDisableAttach = booleanOption(context, "require-disable-attach", true);
        boolean requireDynamicAgentDisabled = booleanOption(context, "require-dynamic-agent-disabled", false);
        boolean rejectAgents = booleanOption(context, "reject-agents", true);
        boolean rejectAttachListener = booleanOption(context, "reject-attach-listener", true);
        String failureAction = stringOption(context, "failure-action", "throw");
        RuntimeRelocator.RuntimeHandle runtime = RuntimeRelocator.inject(
                context,
                RUNTIME_CLASS,
                stringOption(context, "runtime-class", ""),
                stringOption(context, "runtime-method", ""),
                VERIFY_DESC
        );


        int injected = 0;
        for (ClassNode classNode : targets(context)) {
            if (classNode.name.equals(runtime.owner())) {
                continue;
            }
            injectClinit(classNode, buildCall(runtime, requireDisableAttach, requireDynamicAgentDisabled,
                    rejectAgents, rejectAttachListener, failureAction));
            context.pool().markDirty(classNode.name);
            injected++;
        }

        context.stats().add("antiAttachClasses", injected);
        log("Injected anti-attach guard into {} classes", injected);
    }

    private Set<ClassNode> targets(Context context) {
        String coverage = stringOption(context, "coverage", "entrypoints").toLowerCase();
        Set<ClassNode> result = new LinkedHashSet<>();
        if ("all-classes".equals(coverage)) {
            for (ClassNode classNode : context.pool().getClasses()) {
                if (eligible(classNode, context)) {
                    result.add(classNode);
                }
            }
            return result;
        }

        addEntrypoint(context, result, context.jar().getManifestMainClass());
        addEntrypoint(context, result, context.jar().getCurrentPluginMainClass());
        if (result.isEmpty()) {
            for (ClassNode classNode : context.pool().getClasses()) {
                if (eligible(classNode, context)) {
                    result.add(classNode);
                    break;
                }
            }
        }
        return result;
    }

    private void addEntrypoint(Context context, Set<ClassNode> result, String dottedName) {
        if (dottedName == null || dottedName.isBlank()) {
            return;
        }
        ClassNode classNode = context.pool().getClass(dottedName.replace('.', '/'));
        if (classNode != null && eligible(classNode, context)) {
            result.add(classNode);
        }
    }

    private boolean eligible(ClassNode classNode, Context context) {
        if (classNode.name.startsWith("dev/frost/runtime/")) {
            return false;
        }
        if ((classNode.access & Opcodes.ACC_ANNOTATION) != 0) {
            return false;
        }
        return shouldProcess(classNode.name, context.config(),
                context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions());
    }

    private InsnList buildCall(RuntimeRelocator.RuntimeHandle runtime,
                               boolean requireDisableAttach,
                               boolean requireDynamicAgentDisabled,
                               boolean rejectAgents,
                               boolean rejectAttachListener,
                               String failureAction) {
        InsnList insns = new InsnList();
        insns.add(new InsnNode(requireDisableAttach ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        insns.add(new InsnNode(requireDynamicAgentDisabled ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        insns.add(new InsnNode(rejectAgents ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        insns.add(new InsnNode(rejectAttachListener ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        insns.add(new LdcInsnNode(failureAction));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, runtime.owner(), runtime.method(), VERIFY_DESC, false));
        return insns;
    }

    private void injectClinit(ClassNode classNode, InsnList call) {
        for (MethodNode method : classNode.methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                method.instructions.insert(call);
                return;
            }
        }
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(call);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        clinit.maxStack = 5;
        classNode.methods.add(clinit);
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private String stringOption(Context context, String key, String fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : value.toString();
    }
}
