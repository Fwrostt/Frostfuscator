package dev.frost.obfuscator.transformer.flow.ssa;

import dev.frost.ir.analysis.ConstantFact;
import dev.frost.ir.analysis.StandardAnalyses;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Value;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Phase 3.2: guards real conditional def-use chains with a data-derived opaque invariant. */
public final class SsaFlowConditionPass implements MethodPass {
    private final int probability;
    private final int maximum;
    private final int key;

    public SsaFlowConditionPass(int probability, int maximum, int key) {
        this.probability = Math.max(0, Math.min(100, probability));
        this.maximum = Math.max(0, maximum);
        this.key = key;
    }

    @Override public String id() { return "frost.flow.condition-ssa"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (probability == 0 || maximum == 0) return PassResult.unchanged();
        SplittableRandom random = context.randomFor(id());
        var constants = context.analyses().get(method, StandardAnalyses.SCCP);
        List<Plan> plans = new ArrayList<>();
        for (BasicBlock block : List.copyOf(method.blocks())) {
            if (plans.size() >= maximum) break;
            IrInstruction terminator = block.terminator().orElse(null);
            if (terminator == null || !terminator.operation().code().equals(CoreOps.CONDITIONAL_BRANCH)
                    || random.nextInt(100) >= probability
                    || block.normalSuccessors().stream().anyMatch(edge -> !edge.values().isEmpty())) continue;
            List<Value> values = SsaFlowSupport.availableIntValues(method, block, terminator,
                    value -> constants.fact(value) instanceof ConstantFact.Overdefined);
            if (values.isEmpty()) continue;
            plans.add(new Plan(block, terminator, values.get(random.nextInt(values.size()))));
        }
        if (plans.isEmpty()) return PassResult.unchanged();
        long inserted = 0;
        for (Plan plan : plans) {
            Value predicate = SsaFlowSupport.insertEvenProductPredicate(method, plan.block(), plan.terminator(),
                    plan.source(), key ^ (int) inserted * 0x85ebca6b, "arithmetic");
            SsaFlowSupport.guardTerminator(method, plan.block(), predicate,
                    "condition$" + plan.block().name(), true);
            inserted++;
        }
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("guards", inserted));
    }

    private record Plan(BasicBlock block, IrInstruction terminator, Value source) {}
}
