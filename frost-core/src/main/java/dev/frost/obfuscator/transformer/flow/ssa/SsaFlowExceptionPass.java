package dev.frost.obfuscator.transformer.flow.ssa;

import dev.frost.ir.analysis.ConstantFact;
import dev.frost.ir.analysis.StandardAnalyses;
import dev.frost.ir.bytecode.AsmMetadataKeys;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.ReferenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** Phase 4.3: verifier-correct synthetic handler regions guarded by real SSA data. */
public final class SsaFlowExceptionPass implements MethodPass {
    private final int probability;
    private final int maximum;
    private final int key;

    public SsaFlowExceptionPass(int probability, int maximum, int key) {
        this.probability = Math.max(0, Math.min(100, probability));
        this.maximum = Math.max(0, maximum);
        this.key = key;
    }

    @Override public String id() { return "frost.flow.exception-ssa"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (probability == 0 || maximum == 0) return PassResult.unchanged();
        SplittableRandom random = context.randomFor(id());
        var constants = context.analyses().get(method, StandardAnalyses.SCCP);
        List<Plan> plans = new ArrayList<>();
        for (BasicBlock block : List.copyOf(method.blocks())) {
            if (plans.size() >= maximum) break;
            IrInstruction terminator = block.terminator().orElse(null);
            if (terminator == null || random.nextInt(100) >= probability
                    || block.incomingEdges().stream().anyMatch(edge -> edge.kind().isExceptional())
                    || block.normalSuccessors().stream().anyMatch(edge -> !edge.values().isEmpty())) continue;
            List<Value> values = SsaFlowSupport.availableIntValues(method, block, terminator,
                    value -> constants.fact(value) instanceof ConstantFact.Overdefined);
            if (!values.isEmpty()) plans.add(new Plan(block, terminator, values.get(random.nextInt(values.size()))));
        }
        if (plans.isEmpty()) return PassResult.unchanged();

        int priority = method.exceptionRegions().stream().mapToInt(region -> region.priority()).max().orElse(-1) + 1;
        ReferenceType catchType = new ReferenceType("java/lang/NullPointerException", Nullability.NON_NULL);
        long inserted = 0;
        for (Plan plan : plans) {
            Value predicate = SsaFlowSupport.insertEvenProductPredicate(method, plan.block(), plan.terminator(),
                    plan.source(), key ^ (int) inserted * 0xc2b2ae35, "reversible");
            SsaFlowSupport.GuardRewrite rewrite = SsaFlowSupport.guardTerminator(method, plan.block(), predicate,
                    "exception$" + plan.block().name(), false);
            BasicBlock trap = rewrite.falsePath();
            IrInstruction nullValue = SsaFlowSupport.nullConstant(method);
            trap.append(nullValue);
            trap.append(method.createInstruction(CoreOps.THROW, List.of(nullValue.result()), List.of()));
            BasicBlock handler = method.createBlock(SsaFlowSupport.uniqueBlockName(method,
                    "exception$" + plan.block().name() + "$handler"));
            PhiNode exception = handler.addPhi(catchType, "caught$" + inserted);
            exception.metadata().put(AsmMetadataKeys.PHI_SLOT_KIND, "stack");
            exception.metadata().put(AsmMetadataKeys.PHI_SLOT_INDEX, 0L);
            var exceptional = method.connect(trap, handler, EdgeKind.EXCEPTION,
                    catchType.internalName(), catchType, priority);
            Value thrown = exceptional.addValue("exception", catchType).result();
            exception.putInput(exceptional, thrown);
            handler.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
            method.connect(handler, rewrite.continuation(), EdgeKind.NORMAL, "caught", null, 0);
            method.addExceptionRegion(Set.of(trap), handler, catchType, priority);
            priority++;
            inserted++;
        }
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("exceptionGuards", inserted));
    }

    private record Plan(BasicBlock block, IrInstruction terminator, Value source) {}
}
