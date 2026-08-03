package dev.frost.obfuscator.transformer.optimization;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.ir.bytecode.BytecodeImportResult;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.bytecode.ImportCapability;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.transform.IrGraphInliner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

/** Inter-method Frost-IR graph inlining for bounded private static methods. */
public final class AggressiveInliningTransformer extends Transformer {
    private record Member(String owner, String name, String descriptor) {}
    private record Candidate(MethodNode bytecode, BytecodeImportResult imported, int operations) {}

    @Override public String getName() { return "aggressive-inlining"; }
    @Override public String getCategory() { return "Optimization"; }
    @Override public boolean runsPostRemap() { return true; }

    @Override
    public void transform(Context context) {
        int maxInstructions = intOption(context, "max-instructions", 12, 1, 64);
        boolean removeInlined = booleanOption(context, "remove-inlined-methods", true);
        IrContext irContext = IrContext.standard();
        IrGraphInliner inliner = new IrGraphInliner();
        long calls = 0, removed = 0, skipped = 0;

        for (ClassNode node : context.pool().getClasses()) {
            if (!shouldProcess(node.name, context.config(), context.pool().getGlobalExclusions(),
                    context.pool().getGlobalInclusions())) continue;
            Map<Member, Candidate> candidates = discoverCandidates(node, irContext, maxInstructions);
            if (candidates.isEmpty()) continue;
            Set<MethodNode> candidateNodes = new HashSet<>();
            candidates.values().forEach(candidate -> candidateNodes.add(candidate.bytecode()));
            Set<MethodNode> used = new HashSet<>();
            boolean classChanged = false;
            boolean classRemoved = false;

            for (int methodIndex = 0; methodIndex < node.methods.size(); methodIndex++) {
                MethodNode bytecode = node.methods.get(methodIndex);
                if (candidateNodes.contains(bytecode) || bytecode.instructions == null
                        || bytecode.instructions.size() == 0) continue;
                BytecodeImportResult callerImport;
                try {
                    callerImport = new BytecodeSsaImporter(irContext).importMethod(node.name, bytecode);
                } catch (RuntimeException failure) {
                    skipped++;
                    continue;
                }
                if (!callerImport.has(ImportCapability.TYPED_STACK_SSA)) {
                    skipped++;
                    continue;
                }

                List<IrInstruction> originalCalls = callerImport.method().blocks().stream()
                        .flatMap(block -> block.instructions().stream())
                        .filter(instruction -> instruction.operation().code().equals(CoreOps.INVOKE)).toList();
                Set<MethodNode> locallyUsed = new HashSet<>();
                int localCalls = 0;
                try {
                    for (IrInstruction call : originalCalls) {
                        Candidate candidate = candidates.get(invokedMember(call));
                        if (candidate == null || !inliner.check(callerImport.method(), call,
                                candidate.imported().method()).eligible()) continue;
                        inliner.inline(callerImport.method(), call, candidate.imported().method(),
                                "inline$" + methodIndex + "$" + localCalls + "$" + candidate.bytecode().name);
                        locallyUsed.add(candidate.bytecode());
                        localCalls++;
                    }
                    if (localCalls == 0) continue;
                    var lowered = new BytecodeMethodLowerer().lower(callerImport.method(), callerImport);
                    if (!lowered.succeeded()) {
                        skipped += localCalls;
                        continue;
                    }
                    node.methods.set(methodIndex, lowered.output().orElseThrow());
                    calls += localCalls;
                    used.addAll(locallyUsed);
                    classChanged = true;
                } catch (RuntimeException failure) {
                    skipped += Math.max(1, localCalls);
                }
            }

            if (removeInlined && !used.isEmpty()) {
                for (MethodNode candidate : new ArrayList<>(used)) {
                    if (!hasReference(node, candidate)) {
                        node.methods.remove(candidate);
                        removed++;
                        classRemoved = true;
                    }
                }
            }
            if (classChanged || classRemoved) context.pool().markFramesDirty(node.name);
        }
        context.stats().add("inlinedCalls", calls);
        context.stats().add("inlinedMethodsRemoved", removed);
        context.stats().add("irInliningSkippedCallsites", skipped);
        log("Frost-IR inlined {} call sites, removed {} fully inlined methods, skipped {} unsupported sites",
                calls, removed, skipped);
    }

    private Map<Member, Candidate> discoverCandidates(ClassNode owner, IrContext context, int maximum) {
        Map<Member, Candidate> candidates = new HashMap<>();
        for (MethodNode method : owner.methods) {
            if (!accessEligible(method) || hasInstructionTypeAnnotations(method)) continue;
            try {
                BytecodeImportResult imported = new BytecodeSsaImporter(context).importMethod(owner.name, method);
                if (!imported.has(ImportCapability.TYPED_STACK_SSA)) continue;
                int operations = (int) imported.method().blocks().stream()
                        .flatMap(block -> block.instructions().stream())
                        .filter(instruction -> !isProvenanceOnly(instruction)).count();
                if (operations <= 1 || operations > maximum || directlyRecursive(imported, owner.name, method)) continue;
                candidates.put(new Member(owner.name, method.name, method.desc),
                        new Candidate(method, imported, operations));
            } catch (RuntimeException ignored) {
                // Unsupported candidates remain ordinary methods.
            }
        }
        return candidates;
    }

    private boolean accessEligible(MethodNode method) {
        return (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))
                == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) == 0
                && !method.name.startsWith("<") && method.instructions != null
                && (method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty());
    }

    private boolean isProvenanceOnly(IrInstruction instruction) {
        return instruction.operation().code().equals(CoreOps.NOP)
                || instruction.operation().code().equals(CoreOps.LOCAL_WRITE)
                || instruction.operation().code().equals(CoreOps.STACK_PERMUTE);
    }

    private boolean directlyRecursive(BytecodeImportResult imported, String owner, MethodNode method) {
        Member self = new Member(owner, method.name, method.desc);
        return imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.INVOKE))
                .anyMatch(instruction -> self.equals(invokedMember(instruction)));
    }

    private Member invokedMember(IrInstruction instruction) {
        return new Member(stringAttribute(instruction, "owner"), stringAttribute(instruction, "name"),
                stringAttribute(instruction, "descriptor"));
    }

    private String stringAttribute(IrInstruction instruction, String name) {
        IrAttribute value = instruction.operation().attributes().get(name);
        return value instanceof IrAttribute.StringValue string ? string.value() : "";
    }

    private boolean hasInstructionTypeAnnotations(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.visibleTypeAnnotations != null && !instruction.visibleTypeAnnotations.isEmpty()
                    || instruction.invisibleTypeAnnotations != null && !instruction.invisibleTypeAnnotations.isEmpty()) return true;
        }
        return false;
    }

    private boolean hasReference(ClassNode node, MethodNode target) {
        for (MethodNode method : node.methods) {
            if (method == target || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)
                        && call.name.equals(target.name) && call.desc.equals(target.desc)) return true;
                if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    if (matches(dynamic.bsm, node.name, target)
                            || java.util.Arrays.stream(dynamic.bsmArgs)
                            .anyMatch(value -> references(value, node.name, target))) return true;
                }
                if (instruction instanceof LdcInsnNode ldc && references(ldc.cst, node.name, target)) return true;
            }
        }
        return false;
    }

    private boolean references(Object value, String owner, MethodNode target) {
        if (value instanceof Handle handle) return matches(handle, owner, target);
        if (value instanceof ConstantDynamic dynamic) {
            if (matches(dynamic.getBootstrapMethod(), owner, target)) return true;
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                if (references(dynamic.getBootstrapMethodArgument(index), owner, target)) return true;
            }
        }
        return false;
    }

    private boolean matches(Handle handle, String owner, MethodNode target) {
        return handle.getOwner().equals(owner) && handle.getName().equals(target.name)
                && handle.getDesc().equals(target.desc) && handle.getTag() > Opcodes.H_PUTSTATIC;
    }

    private int intOption(Context context, String key, int fallback, int min, int max) {
        try {
            Object value = context.config().getOptions().get(key);
            int result = value == null ? fallback : Integer.parseInt(value.toString());
            return Math.max(min, Math.min(max, result));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
