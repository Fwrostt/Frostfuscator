package dev.frost.obfuscator.transformer.phase5;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Use;
import dev.frost.ir.model.Value;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.type.SpecialType;
import dev.frost.ir.type.UninitializedType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Late SSA register-noise pass. It gives selected values a distinct SSA definition and reroutes
 * their existing uses through it. Frost-IR's lowering assigns the copy a separate JVM local,
 * producing verifier-safe register traffic without reasoning about the operand stack.
 */
public final class SsaCopyWeavingPass implements MethodPass {
    private final String id;
    private final int probability;
    private final int maximumCopies;

    public SsaCopyWeavingPass(String id, int probability, int maximumCopies) {
        this.id = id;
        this.probability = Math.max(0, Math.min(100, probability));
        this.maximumCopies = Math.max(0, maximumCopies);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (maximumCopies == 0 || probability == 0) return PassResult.unchanged();
        SplittableRandom random = context.randomFor(id);
        int inserted = 0;

        for (BasicBlock block : List.copyOf(method.blocks())) {
            for (IrInstruction definition : List.copyOf(block.instructions())) {
                if (inserted >= maximumCopies) break;
                if (definition.isTerminator() || definition.results().size() != 1
                        || definition.operation().code().equals(CoreOps.COPY)) continue;
                Value value = definition.result();
                if (!value.isUsed() || value.type().slots() <= 0
                        || value.type() instanceof SpecialType
                        || value.type() instanceof UninitializedType
                        || random.nextInt(100) >= probability) continue;

                List<Use> existingUses = new ArrayList<>(value.uses());
                IrInstruction copy = method.createInstruction(CoreOps.COPY, List.of(value), List.of(value.type()));
                int definitionIndex = block.instructions().indexOf(definition);
                block.insert(definitionIndex + 1, copy);
                for (Use use : existingUses) use.replaceWith(copy.result());
                inserted++;
            }
            if (inserted >= maximumCopies) break;
        }

        if (inserted == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("copies", (long) inserted));
    }
}
