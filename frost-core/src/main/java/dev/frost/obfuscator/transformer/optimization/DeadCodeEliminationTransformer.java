package dev.frost.obfuscator.transformer.optimization;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Reachability-based removal limited to private members. Public/package APIs,
 * constructors, class initializers, native methods, and externally callable members
 * are roots, keeping this pass useful without guessing about whole-program reflection.
 */
public final class DeadCodeEliminationTransformer extends Transformer {
    private record Member(String owner, String name, String desc) {
    }

    @Override
    public String getName() {
        return "dead-code-elimination";
    }

    @Override
    public String getCategory() {
        return "Optimization";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        Map<Member, MethodNode> methods = new HashMap<>();
        Map<Member, FieldNode> fields = new HashMap<>();
        Deque<Member> work = new ArrayDeque<>();
        Set<Member> reachableMethods = new HashSet<>();
        Set<Member> reachableFields = new HashSet<>();

        for (ClassNode node : context.pool().getClasses()) {
            for (MethodNode method : node.methods) {
                Member key = new Member(node.name, method.name, method.desc);
                methods.put(key, method);
                if (isRoot(method)) {
                    reachableMethods.add(key);
                    work.add(key);
                }
            }
            for (FieldNode field : node.fields) {
                fields.put(new Member(node.name, field.name, field.desc), field);
            }
        }

        while (!work.isEmpty()) {
            Member key = work.removeFirst();
            MethodNode method = methods.get(key);
            if (method == null || method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    markMethod(new Member(call.owner, call.name, call.desc), methods, reachableMethods, work);
                } else if (insn instanceof FieldInsnNode field) {
                    reachableFields.add(new Member(field.owner, field.name, field.desc));
                } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    markHandle(dynamic.bsm, methods, fields, reachableMethods, reachableFields, work);
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle) {
                            markHandle(handle, methods, fields, reachableMethods, reachableFields, work);
                        } else if (argument instanceof Type type && type.getSort() == Type.METHOD) {
                            // Method types contain no owner, but retaining all matching private descriptors
                            // avoids deleting common lambda implementation bridges.
                            methods.keySet().stream().filter(m -> m.desc.equals(type.getDescriptor()))
                                    .forEach(m -> markMethod(m, methods, reachableMethods, work));
                        }
                    }
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                    markHandle(handle, methods, fields, reachableMethods, reachableFields, work);
                }
            }
        }

        boolean removeFields = booleanOption(context, "remove-private-fields", true);
        int removedMethods = 0;
        int removedFields = 0;
        for (ClassNode node : context.pool().getClasses()) {
            if (!shouldProcess(node.name, context.config(), context.pool().getGlobalExclusions(),
                    context.pool().getGlobalInclusions())) continue;
            for (Iterator<MethodNode> iterator = node.methods.iterator(); iterator.hasNext(); ) {
                MethodNode method = iterator.next();
                Member key = new Member(node.name, method.name, method.desc);
                if ((method.access & Opcodes.ACC_PRIVATE) != 0
                        && !method.name.startsWith("<")
                        && !reachableMethods.contains(key)) {
                    iterator.remove();
                    removedMethods++;
                }
            }
            if (removeFields) {
                for (Iterator<FieldNode> iterator = node.fields.iterator(); iterator.hasNext(); ) {
                    FieldNode field = iterator.next();
                    Member key = new Member(node.name, field.name, field.desc);
                    if ((field.access & Opcodes.ACC_PRIVATE) != 0
                            && !reachableFields.contains(key)
                            && (field.value == null || (field.access & Opcodes.ACC_STATIC) == 0)) {
                        iterator.remove();
                        removedFields++;
                    }
                }
            }
            if (removedMethods > 0 || removedFields > 0) context.pool().markDirty(node.name);
        }
        context.stats().add("deadMethodsRemoved", removedMethods);
        context.stats().add("deadFieldsRemoved", removedFields);
        log("Removed {} unreachable private methods and {} unused private fields", removedMethods, removedFields);
    }

    private boolean isRoot(MethodNode method) {
        return method.name.startsWith("<")
                || (method.access & Opcodes.ACC_PRIVATE) == 0
                || (method.access & Opcodes.ACC_NATIVE) != 0;
    }

    private void markMethod(Member key, Map<Member, MethodNode> methods, Set<Member> reachable,
                            Deque<Member> work) {
        if (methods.containsKey(key) && reachable.add(key)) work.add(key);
    }

    private void markHandle(Handle handle, Map<Member, MethodNode> methods, Map<Member, FieldNode> fields,
                            Set<Member> reachableMethods, Set<Member> reachableFields, Deque<Member> work) {
        Member key = new Member(handle.getOwner(), handle.getName(), handle.getDesc());
        if (handle.getTag() <= Opcodes.H_PUTSTATIC) reachableFields.add(key);
        else markMethod(key, methods, reachableMethods, work);
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
