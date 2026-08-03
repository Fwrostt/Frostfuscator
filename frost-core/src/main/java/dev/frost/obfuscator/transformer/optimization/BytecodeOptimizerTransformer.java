package dev.frost.obfuscator.transformer.optimization;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.bytecode.ImportCapability;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.pass.CommonSubexpressionEliminationPass;
import dev.frost.ir.pass.ConstantFoldingPass;
import dev.frost.ir.pass.CopyPropagationPass;
import dev.frost.ir.pass.DeadCodeEliminationPass;
import dev.frost.ir.pass.CriticalEdgeSplittingPass;
import dev.frost.ir.pass.UnreachableBlockEliminationPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import java.util.concurrent.atomic.LongAdder;

public class BytecodeOptimizerTransformer extends Transformer {

    @Override
    public String getName() {
        return "bytecode-optimizer";
    }

    @Override
    public String getCategory() {
        return "Optimization";
    }

    @Override
    public void transform(Context context) {
        LongAdder optimizedMethods = new LongAdder();
        LongAdder skippedMethods = new LongAdder();
        LongAdder removed = new LongAdder();
        IrContext irContext = IrContext.standard();
        // SSA import retains verifier frame snapshots until lowering completes. Process classes
        // sequentially so large, already-expanded obfuscation pipelines cannot multiply that
        // per-method memory by the ClassPool fork-join parallelism.
        for (ClassNode classNode : context.pool().getClasses()) {
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())) {
                continue;
            }
            boolean classChanged = false;
            for (int methodIndex = 0; methodIndex < classNode.methods.size(); methodIndex++) {
                MethodNode method = classNode.methods.get(methodIndex);
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if (exceedsSsaImportBudget(method)) {
                    skippedMethods.increment();
                    continue;
                }
                var imported = new BytecodeSsaImporter(irContext).importMethod(classNode.name, method);
                if (!imported.has(ImportCapability.TYPED_STACK_SSA)) {
                    skippedMethods.increment();
                    continue;
                }
                var pipeline = new PassManager().add(new ConstantFoldingPass()).add(new CopyPropagationPass())
                        .add(new UnreachableBlockEliminationPass()).add(new CommonSubexpressionEliminationPass())
                        .add(new CopyPropagationPass()).add(new DeadCodeEliminationPass())
                        .add(new CriticalEdgeSplittingPass())
                        .run(imported.method(), new PassContext(new AnalysisManager(), stableSeed(classNode, method)));
                if (!pipeline.changed()) continue;
                var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
                if (!lowered.succeeded()) {
                    skippedMethods.increment();
                    continue;
                }
                classNode.methods.set(methodIndex, lowered.output().orElseThrow());
                optimizedMethods.increment();
                pipeline.metrics().values().forEach(metrics -> {
                    Long count = metrics.get("removed");
                    if (count != null) removed.add(count);
                    Long eliminated = metrics.get("eliminated");
                    if (eliminated != null) removed.add(eliminated);
                    Long folded = metrics.get("folded");
                    if (folded != null) removed.add(folded);
                });
                classChanged = true;
            }
            if (classChanged) {
                context.pool().markFramesDirty(classNode.name);
            }
        }
        context.stats().add("irOptimizedMethods", optimizedMethods.sum());
        context.stats().add("irEliminatedOperations", removed.sum());
        context.stats().add("irSkippedMethods", skippedMethods.sum());
        log("Frost-IR optimized {} methods, eliminated {} operations, skipped {} unsupported methods",
                optimizedMethods.sum(), removed.sum(), skippedMethods.sum());
    }

    private boolean exceedsSsaImportBudget(MethodNode method) {
        int instructions = method.instructions == null ? 0 : method.instructions.size();
        long frameWidth = Math.max(1, method.maxLocals) + (long) Math.max(1, method.maxStack);
        return instructions > 4_096 || instructions * frameWidth > 1_000_000L;
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
}
