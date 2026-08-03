package dev.frost.obfuscator.transformer.ir;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.bytecode.ImportCapability;
import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import dev.frost.obfuscator.util.ASMHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Transactional bridge from an ASM method to one validated Frost-IR method pass and back.
 * The source {@link MethodNode} is never mutated. Callers publish {@link Result#output()} only
 * for a {@link Status#CHANGED} result and retain the original method for every other outcome.
 */
public final class IrMethodPassAdapter {
    private static final Semaphore GLOBAL_EXECUTION_SLOTS = new Semaphore(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), true);
    private static final int MAX_INPUT_INSTRUCTIONS = 4_096;
    private static final long MAX_INPUT_FRAME_CELLS = 1_000_000L;
    private static final int MAX_MUTATED_IR_INSTRUCTIONS = 4_096;
    private static final long MAX_LOWERING_COMPLEXITY = 1_000_000L;

    private final IrContext irContext;

    public IrMethodPassAdapter() {
        this(IrContext.standard());
    }

    public IrMethodPassAdapter(IrContext irContext) {
        this.irContext = Objects.requireNonNull(irContext, "irContext");
    }

    public Result run(String owner, MethodNode source, MethodPass pass, long seed) {
        GLOBAL_EXECUTION_SLOTS.acquireUninterruptibly();
        try {
            return runExclusive(owner, source, pass, seed);
        } finally {
            GLOBAL_EXECUTION_SLOTS.release();
        }
    }

    private Result runExclusive(String owner, MethodNode source, MethodPass pass, long seed) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(pass, "pass");

        String inputBudgetFailure = inputBudgetFailure(source);
        if (!inputBudgetFailure.isEmpty()) {
            return new Result(Status.UNSUPPORTED, null, Map.of(), List.of(), inputBudgetFailure);
        }

        final dev.frost.ir.bytecode.BytecodeImportResult imported;
        try {
            imported = new BytecodeSsaImporter(irContext).importMethod(owner, prepareForAnalysis(source));
        } catch (RuntimeException exception) {
            return new Result(Status.UNSUPPORTED, null, Map.of(), List.of(),
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception));
        }
        List<Diagnostic> diagnostics = new ArrayList<>(imported.diagnostics());
        if (!imported.has(ImportCapability.TYPED_STACK_SSA)
                || !imported.has(ImportCapability.LOWERABLE_AFTER_MUTATION)) {
            return new Result(Status.UNSUPPORTED, null, Map.of(), diagnostics,
                    "Method does not have complete, mutation-lowerable typed SSA");
        }

        PassManager.PipelineResult pipeline;
        try {
            pipeline = new PassManager().add(pass).run(imported.method(),
                    new PassContext(new AnalysisManager(), seed));
        } catch (RuntimeException exception) {
            return new Result(Status.PASS_FAILED, null, Map.of(), diagnostics,
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception));
        }
        diagnostics.addAll(pipeline.diagnostics());
        Map<String, Long> metrics = new LinkedHashMap<>(
                pipeline.metrics().getOrDefault(pass.id(), Map.of()));
        if (!pipeline.changed()) {
            return new Result(Status.UNCHANGED, null, metrics, diagnostics, "");
        }
        String loweringBudgetFailure = loweringBudgetFailure(imported.method());
        if (!loweringBudgetFailure.isEmpty()) {
            return new Result(Status.LOWERING_FAILED, null, metrics, diagnostics, loweringBudgetFailure);
        }

        final dev.frost.ir.bytecode.BytecodeLoweringResult lowered;
        try {
            lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
        } catch (RuntimeException exception) {
            return new Result(Status.LOWERING_FAILED, null, metrics, diagnostics,
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception));
        }
        diagnostics.addAll(lowered.diagnostics());
        if (!lowered.succeeded()) {
            return new Result(Status.LOWERING_FAILED, null, metrics, diagnostics,
                    "Frost-IR lowering or bytecode verification failed");
        }
        return new Result(Status.CHANGED, lowered.output().orElseThrow(), metrics, diagnostics, "");
    }

    /** Publishes a lowered body while preserving the caller-visible MethodNode identity. */
    public static void publishBody(MethodNode target, MethodNode output) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(output, "output");
        target.instructions = output.instructions;
        target.tryCatchBlocks = output.tryCatchBlocks;
        target.localVariables = output.localVariables;
        target.visibleLocalVariableAnnotations = output.visibleLocalVariableAnnotations;
        target.invisibleLocalVariableAnnotations = output.invisibleLocalVariableAnnotations;
        target.maxStack = output.maxStack;
        target.maxLocals = output.maxLocals;
    }

    /** Removes the synthetic layout label emitted for a branchless, metadata-free entry block. */
    public static void removeUnreferencedEntryLabel(MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()
                || method.localVariables != null && !method.localVariables.isEmpty()) return;
        while (method.instructions.getFirst() instanceof LabelNode entry) {
            boolean referenced = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof JumpInsnNode jump && jump.label == entry) referenced = true;
                if (instruction instanceof TableSwitchInsnNode table
                        && (table.dflt == entry || table.labels.contains(entry))) referenced = true;
                if (instruction instanceof LookupSwitchInsnNode lookup
                        && (lookup.dflt == entry || lookup.labels.contains(entry))) referenced = true;
                if (instruction instanceof LineNumberNode line && line.start == entry) referenced = true;
            }
            if (referenced) return;
            method.instructions.remove(entry);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "no detail" : exception.getMessage();
    }

    private static String inputBudgetFailure(MethodNode source) {
        int instructions = source.instructions == null ? 0 : source.instructions.size();
        long frameWidth = Math.max(1, source.maxLocals) + (long) Math.max(1, source.maxStack);
        long frameCells = instructions * frameWidth;
        if (instructions > MAX_INPUT_INSTRUCTIONS || frameCells > MAX_INPUT_FRAME_CELLS) {
            return "Method exceeds the safe SSA import analysis budget";
        }
        return "";
    }

    private static String loweringBudgetFailure(dev.frost.ir.model.IrMethod method) {
        int instructions = method.blocks().stream()
                .mapToInt(block -> block.instructions().size()).sum();
        long values = method.parameters().size();
        for (var block : method.blocks()) {
            values += block.phis().size();
            values += block.instructions().stream().mapToLong(instruction -> instruction.results().size()).sum();
        }
        long complexity = instructions * Math.max(1L, values);
        if (instructions > MAX_MUTATED_IR_INSTRUCTIONS || complexity > MAX_LOWERING_COMPLEXITY) {
            return "Mutated method exceeds the safe SSA lowering analysis budget";
        }
        return "";
    }

    /**
     * ASM's analyzer trusts the declared frame capacities. Class-reader methods already have
     * correct values, but programmatically assembled methods (including plugin/test fixtures)
     * commonly leave them at zero. Normalize only an isolated copy so the adapter remains
     * transactional and the caller's method is never changed on a fallback.
     */
    private static MethodNode prepareForAnalysis(MethodNode source) {
        int requiredLocals = minimumLocals(source);
        if (source.maxStack > 0 && source.maxLocals >= requiredLocals) return source;

        List<String> exceptions = source.exceptions == null ? List.of() : source.exceptions;
        MethodNode prepared = new MethodNode(Opcodes.ASM9, source.access, source.name, source.desc,
                source.signature, exceptions.toArray(String[]::new));
        source.accept(prepared);
        prepared.maxLocals = Math.max(prepared.maxLocals, requiredLocals);
        prepared.maxStack = Math.max(prepared.maxStack, 64);
        return prepared;
    }

    private static int minimumLocals(MethodNode method) {
        int arguments = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) arguments += argument.getSize();
        return Math.max(arguments, ASMHelper.nextFreeLocal(method));
    }

    public enum Status {
        CHANGED,
        UNCHANGED,
        UNSUPPORTED,
        PASS_FAILED,
        LOWERING_FAILED
    }

    public record Result(Status status, MethodNode transformedMethod, Map<String, Long> metrics,
                         List<Diagnostic> diagnostics, String message) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            metrics = Map.copyOf(metrics == null ? Map.of() : metrics);
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
            message = message == null ? "" : message;
            if (status == Status.CHANGED && transformedMethod == null) {
                throw new IllegalArgumentException("Changed result requires transformed bytecode");
            }
            if (status != Status.CHANGED && transformedMethod != null) {
                throw new IllegalArgumentException("Only changed results may contain transformed bytecode");
            }
        }

        public boolean changed() {
            return status == Status.CHANGED;
        }

        public Optional<MethodNode> output() {
            return Optional.ofNullable(transformedMethod);
        }

        public long metric(String name) {
            return metrics.getOrDefault(name, 0L);
        }
    }
}
