package dev.frost.ir.pass;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Eliminates explicit copies and trivial phis through exact-type SSA use rewriting. */
public final class CopyPropagationPass implements MethodPass {
    @Override public String id() { return "frost.copy-propagation"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        int propagated = 0;
        boolean progress;
        do {
            progress = false;
            for (var block : method.blocks()) {
                for (PhiNode phi : List.copyOf(block.phis())) {
                    Value replacement = trivialInput(phi);
                    if (replacement == null || !replacement.type().equals(phi.result().type())) continue;
                    phi.result().replaceAllUsesWith(replacement);
                    block.removePhi(phi);
                    propagated++;
                    progress = true;
                }
                for (IrInstruction instruction : List.copyOf(block.instructions())) {
                    if (!instruction.operation().code().equals(CoreOps.COPY)
                            || instruction.operands().size() != 1 || instruction.results().size() != 1) continue;
                    Value replacement = instruction.operands().getFirst();
                    if (!replacement.type().equals(instruction.result().type())) continue;
                    instruction.result().replaceAllUsesWith(replacement);
                    instruction.erase();
                    propagated++;
                    progress = true;
                }
            }
        } while (progress);
        if (propagated == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("propagated", (long) propagated));
    }

    private Value trivialInput(PhiNode phi) {
        Set<Value> distinct = new LinkedHashSet<>();
        phi.inputs().values().stream().filter(value -> value != phi.result()).forEach(distinct::add);
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }
}
