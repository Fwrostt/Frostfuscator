package dev.frost.obfuscator.transformer.flow.ssa;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Value;
import dev.frost.ir.analysis.ConstantFact;
import dev.frost.ir.analysis.StandardAnalyses;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/** Phase 3.1: opaque guards whose invariant is derived from dominating application SSA values. */
public final class SsaOpaquePredicatePass implements MethodPass {
    private final boolean guardBranches;
    private final boolean guardOtherTerminators;
    private final int probability;
    private final int maximum;
    private final int key;
    private final String families;
    private final String sources;
    private final int camouflageRate;

    public SsaOpaquePredicatePass(boolean guardBranches, boolean guardOtherTerminators,
                                  int probability, int maximum, int key, String families,
                                  String sources, int camouflageRate) {
        this.guardBranches = guardBranches;
        this.guardOtherTerminators = guardOtherTerminators;
        this.probability = Math.max(0, Math.min(100, probability));
        this.maximum = Math.max(0, maximum);
        this.key = key;
        this.families = families == null ? "arithmetic" : families;
        this.sources = sources == null ? "" : sources;
        this.camouflageRate = Math.max(0, Math.min(100, camouflageRate));
    }

    @Override public String id() { return "frost.flow.opaque-ssa"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (maximum == 0) return PassResult.unchanged();
        SplittableRandom random = context.randomFor(id());
        var constants = context.analyses().get(method, StandardAnalyses.SCCP);
        List<String> configuredFamilies = parsedFamilies();
        List<Plan> plans = new ArrayList<>();
        for (BasicBlock block : List.copyOf(method.blocks())) {
            if (plans.size() >= maximum) break;
            IrInstruction terminator = block.terminator().orElse(null);
            if (terminator == null || block.incomingEdges().stream().anyMatch(edge -> edge.kind().isExceptional())) continue;
            boolean branch = terminator.operation().code().equals(CoreOps.BRANCH);
            if ((branch ? !guardBranches : !guardOtherTerminators)
                    || random.nextInt(100) >= probability) continue;
            List<Value> available = SsaFlowSupport.availableIntValues(method, block, terminator,
                    value -> constants.fact(value) instanceof ConstantFact.Overdefined);
            if (available.isEmpty() || block.normalSuccessors().stream().anyMatch(edge -> !edge.values().isEmpty())) continue;
            Value source = available.get(random.nextInt(available.size()));
            String family = configuredFamilies.get(random.nextInt(configuredFamilies.size()));
            plans.add(new Plan(block, terminator, source, family));
        }
        if (plans.isEmpty()) return PassResult.unchanged();

        long inserted = 0;
        boolean camouflage = random.nextInt(100) < camouflageRate;
        for (Plan plan : plans) {
            if (camouflage && inserted == 0) {
                SsaFlowSupport.emitRuntimeCamouflage(method, plan.block(), plan.terminator(), sources);
            }
            Value predicate = SsaFlowSupport.insertEvenProductPredicate(method, plan.block(), plan.terminator(),
                    plan.source(), key ^ (int) inserted * 0x9e3779b9, plan.family());
            SsaFlowSupport.guardTerminator(method, plan.block(), predicate,
                    "opaque$" + plan.block().name(), true);
            inserted++;
        }
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("predicates", inserted));
    }

    private List<String> parsedFamilies() {
        List<String> parsed = new ArrayList<>();
        for (String token : families.split("[,;\\s]+")) {
            String family = token.trim().replace('_', '-').toLowerCase(Locale.ROOT);
            if (family.equals("arithmetic") || family.equals("bitwise") || family.equals("reversible")) {
                parsed.add(family);
            } else if (!family.isBlank()) {
                // Families requiring class state are represented by the overflow-safe arithmetic invariant.
                parsed.add("arithmetic");
            }
        }
        return parsed.isEmpty() ? List.of("arithmetic") : List.copyOf(parsed);
    }

    private record Plan(BasicBlock block, IrInstruction terminator, Value source, String family) {}
}
