package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.Set;

public final class RuntimeSelfChecksumTransformer extends Transformer {
    private static final String RUNTIME_CLASS = "dev/frost/runtime/SelfChecksumRuntime";
    private static final String VERIFY_DESC = "(Ljava/lang/Class;Ljava/lang/String;)V";

    @Override
    public String getName() {
        return "runtime-self-checksum";
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
        String failureAction = stringOption(context, "failure-action", "throw");
        int maxClasses = intOption(context, "max-classes", 64, 1, 10000);
        RuntimeRelocator.RuntimeHandle runtime = RuntimeRelocator.inject(
                context,
                RUNTIME_CLASS,
                stringOption(context, "runtime-class", ""),
                stringOption(context, "runtime-method", ""),
                VERIFY_DESC
        );

        Set<ClassNode> targets = targets(context, maxClasses);
        Set<String> targetNames = new LinkedHashSet<>();
        for (ClassNode classNode : targets) {
            injectClinit(classNode, buildCall(runtime, classNode.name, failureAction));
            context.pool().markDirty(classNode.name);
            targetNames.add(classNode.name);
        }
        context.jar().setRuntimeChecksumClasses(targetNames);
        context.stats().add("runtimeChecksumClasses", targetNames.size());
        log("Injected runtime checksum guards into {} classes", targetNames.size());
    }

    private Set<ClassNode> targets(Context context, int maxClasses) {
        String coverage = stringOption(context, "coverage", "entrypoints").toLowerCase();
        Set<ClassNode> result = new LinkedHashSet<>();
        if ("all-classes".equals(coverage)) {
            for (ClassNode classNode : context.pool().getClasses()) {
                if (result.size() >= maxClasses) {
                    break;
                }
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

    private InsnList buildCall(RuntimeRelocator.RuntimeHandle runtime, String className, String failureAction) {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(Type.getObjectType(className)));
        insns.add(new LdcInsnNode(failureAction));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, runtime.owner(), runtime.method(),
                VERIFY_DESC, false));
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
        clinit.maxStack = 2;
        classNode.methods.add(clinit);
    }

    private int intOption(Context context, String key, int fallback, int min, int max) {
        try {
            Object value = context.config().getOptions().get(key);
            int parsed = value == null ? fallback : Integer.parseInt(value.toString());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String stringOption(Context context, String key, String fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : value.toString();
    }
}
