package dev.frost.obfuscator.transformer.optimization;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.bytecode.ImportCapability;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.pass.DeadCodeEliminationPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import dev.frost.ir.pass.UnreachableBlockEliminationPass;
import org.objectweb.asm.Handle;
import org.objectweb.asm.ConstantDynamic;
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
        LocalDceStats localDce = eliminateDeadInstructions(context);
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
                        markConstant(argument, methods, fields, reachableMethods, reachableFields, work);
                    }
                } else if (insn instanceof LdcInsnNode ldc) {
                    markConstant(ldc.cst, methods, fields, reachableMethods, reachableFields, work);
                }
            }
        }

        boolean removeFields = booleanOption(context, "remove-private-fields", true);
        int removedMethods = 0;
        int removedFields = 0;
        for (ClassNode node : context.pool().getClasses()) {
            if (!shouldProcess(node.name, context.config(), context.pool().getGlobalExclusions(),
                    context.pool().getGlobalInclusions())) continue;
            boolean classChanged = false;
            for (Iterator<MethodNode> iterator = node.methods.iterator(); iterator.hasNext(); ) {
                MethodNode method = iterator.next();
                Member key = new Member(node.name, method.name, method.desc);
                if ((method.access & Opcodes.ACC_PRIVATE) != 0
                        && !method.name.startsWith("<")
                        && !reachableMethods.contains(key)) {
                    iterator.remove();
                    removedMethods++;
                    classChanged = true;
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
                        classChanged = true;
                    }
                }
            }
            if (classChanged) context.pool().markDirty(node.name);
        }
        context.stats().add("irDceOptimizedMethods", localDce.optimizedMethods());
        context.stats().add("irDeadOperationsRemoved", localDce.removedOperations());
        context.stats().add("irDceSkippedMethods", localDce.skippedMethods());
        context.stats().add("deadMethodsRemoved", removedMethods);
        context.stats().add("deadFieldsRemoved", removedFields);
        log("Frost-IR removed {} dead operations in {} methods; removed {} unreachable private methods and {} unused private fields",
                localDce.removedOperations(), localDce.optimizedMethods(), removedMethods, removedFields);
    }

    private LocalDceStats eliminateDeadInstructions(Context context) {
        IrContext irContext = IrContext.standard();
        long optimized = 0, removed = 0, skipped = 0;
        for (ClassNode node : context.pool().getClasses()) {
            if (!shouldProcess(node.name, context.config(), context.pool().getGlobalExclusions(),
                    context.pool().getGlobalInclusions())) continue;
            boolean changedClass = false;
            for (int index = 0; index < node.methods.size(); index++) {
                MethodNode method = node.methods.get(index);
                if (method.instructions == null || method.instructions.size() == 0) continue;
                try {
                    var imported = new BytecodeSsaImporter(irContext).importMethod(node.name, method);
                    if (!imported.has(ImportCapability.TYPED_STACK_SSA)) {
                        skipped++;
                        continue;
                    }
                    var result = new PassManager().add(new UnreachableBlockEliminationPass())
                            .add(new DeadCodeEliminationPass())
                            .run(imported.method(), new PassContext(new AnalysisManager(), stableSeed(node, method)));
                    if (!result.changed()) continue;
                    var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
                    if (!lowered.succeeded()) {
                        skipped++;
                        continue;
                    }
                    node.methods.set(index, lowered.output().orElseThrow());
                    optimized++;
                    removed += result.metrics().values().stream()
                            .mapToLong(metrics -> metrics.getOrDefault("removed", 0L)).sum();
                    changedClass = true;
                } catch (RuntimeException failure) {
                    skipped++;
                }
            }
            if (changedClass) context.pool().markFramesDirty(node.name);
        }
        return new LocalDceStats(optimized, removed, skipped);
    }

    private long stableSeed(ClassNode owner, MethodNode method) {
        long hash = 0xcbf29ce484222325L;
        String identity = owner.name + '.' + method.name + method.desc;
        for (int index = 0; index < identity.length(); index++) {
            hash ^= identity.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private record LocalDceStats(long optimizedMethods, long removedOperations, long skippedMethods) {}

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

    private void markConstant(Object constant, Map<Member, MethodNode> methods, Map<Member, FieldNode> fields,
                              Set<Member> reachableMethods, Set<Member> reachableFields, Deque<Member> work) {
        if (constant instanceof Handle handle) {
            markHandle(handle, methods, fields, reachableMethods, reachableFields, work);
        } else if (constant instanceof Type type && type.getSort() == Type.METHOD) {
            // A method type has no owner. Retain descriptor matches so lambda bridges and method handles survive.
            methods.keySet().stream().filter(member -> member.desc.equals(type.getDescriptor()))
                    .forEach(member -> markMethod(member, methods, reachableMethods, work));
        } else if (constant instanceof ConstantDynamic dynamic) {
            markHandle(dynamic.getBootstrapMethod(), methods, fields, reachableMethods, reachableFields, work);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                markConstant(dynamic.getBootstrapMethodArgument(index), methods, fields,
                        reachableMethods, reachableFields, work);
            }
        }
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
