package dev.frost.ir.pass;

import dev.frost.ir.analysis.ConstantFact;
import dev.frost.ir.analysis.SparseConditionalConstants;
import dev.frost.ir.analysis.StandardAnalyses;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.PhiNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** SCCP-backed pure constant folding and executable-edge pruning. */
public final class ConstantFoldingPass implements MethodPass {
    @Override public String id() { return "frost.sccp-fold"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        SparseConditionalConstants constants = context.analyses().get(method, StandardAnalyses.SCCP);
        int folded = 0;
        int pruned = 0;
        for (BasicBlock block : method.blocks()) {
            for (PhiNode phi : new ArrayList<>(block.phis())) {
                ConstantFact fact = constants.fact(phi.result());
                if (!(fact instanceof ConstantFact.Known known)) continue;
                IrInstruction replacement = constant(method, known);
                block.insert(0, replacement);
                replacement.result().setDebugName(phi.result().debugName());
                phi.result().replaceAllUsesWith(replacement.result());
                block.removePhi(phi);
                folded++;
            }
            List<IrInstruction> snapshot = new ArrayList<>(block.instructions());
            for (IrInstruction instruction : snapshot) {
                if (instruction.isTerminator() || instruction.results().size() != 1
                        || instruction.operation().code().equals(CoreOps.CONSTANT)) continue;
                ConstantFact fact = constants.fact(instruction.result());
                if (!(fact instanceof ConstantFact.Known known)) continue;
                var schema = method.context().schema(instruction.operation().code()).orElseThrow();
                if (!schema.effects().isPure()) continue;
                IrInstruction replacement = constant(method, known);
                int index = block.instructions().indexOf(instruction);
                block.insert(index, replacement);
                instruction.metadata().copyPersistentTo(replacement.metadata());
                replacement.result().setDebugName(instruction.result().debugName());
                instruction.result().replaceAllUsesWith(replacement.result());
                block.remove(instruction);
                folded++;
            }
        }
        for (BasicBlock block : method.blocks()) {
            IrInstruction terminator = block.terminator().orElse(null);
            if (terminator == null || !(terminator.operation().code().equals(CoreOps.CONDITIONAL_BRANCH)
                    || terminator.operation().code().equals(CoreOps.SWITCH))) continue;
            List<ControlEdge> executable = block.normalSuccessors().stream().filter(constants::isExecutable).toList();
            if (executable.size() != 1) continue;
            List<ControlEdge> dead = block.normalSuccessors().stream().filter(edge -> edge != executable.getFirst()).toList();
            dead.forEach(method::disconnect);
            block.remove(terminator);
            block.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
            pruned += dead.size();
        }
        boolean changed = folded + pruned > 0;
        return new PassResult(changed, changed ? PreservedAnalyses.none() : PreservedAnalyses.all(),
                List.of(), Map.of("folded", (long) folded, "prunedEdges", (long) pruned));
    }

    private IrInstruction constant(IrMethod method, ConstantFact.Known known) {
        return method.createInstruction(new Operation(CoreOps.CONSTANT, Map.of("value", known.value())),
                List.of(), List.of(known.type()));
    }
}
