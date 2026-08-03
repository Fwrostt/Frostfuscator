package dev.frost.ir.pass;

import dev.frost.ir.analysis.DominatorTree;
import dev.frost.ir.analysis.GlobalValueNumbering;
import dev.frost.ir.analysis.StandardAnalyses;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.OperationTrait;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Dominance-safe elimination of redundant pure SSA expressions. */
public final class CommonSubexpressionEliminationPass implements MethodPass {
    @Override public String id() { return "frost.gvn-cse"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        GlobalValueNumbering gvn = context.analyses().get(method, StandardAnalyses.GVN);
        DominatorTree dominators = context.analyses().get(method, StandardAnalyses.DOMINATORS);
        Map<IrInstruction, Integer> positions = positions(method);
        List<IrInstruction> candidates = method.blocks().stream()
                .flatMap(block -> block.instructions().stream()).filter(this::eligible).toList();
        Map<Value, Boolean> erased = new IdentityHashMap<>();
        int eliminated = 0;
        for (IrInstruction candidate : candidates) {
            Value result = candidate.result();
            Value replacement = gvn.equivalentValues(result).stream()
                    .filter(value -> value != result && !erased.containsKey(value))
                    .filter(value -> dominates(value, candidate, dominators, positions))
                    .findFirst().orElse(null);
            if (replacement == null) continue;
            result.replaceAllUsesWith(replacement);
            candidate.block().orElseThrow().remove(candidate);
            erased.put(result, Boolean.TRUE);
            eliminated++;
        }
        if (eliminated == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("eliminated", (long) eliminated));
    }

    private boolean eligible(IrInstruction instruction) {
        if (instruction.results().size() != 1 || !instruction.effects().isPure()) return false;
        return instruction.method().context().schema(instruction.operation().code())
                .map(schema -> schema.hasTrait(OperationTrait.SPECULATABLE)).orElse(false);
    }

    private boolean dominates(Value value, IrInstruction use, DominatorTree dominators,
                              Map<IrInstruction, Integer> positions) {
        if (value.definition() instanceof dev.frost.ir.model.MethodParameter) return true;
        if (value.definition() instanceof dev.frost.ir.model.EdgeValue) return false;
        BasicBlock definitionBlock = value.definition().definingBlock().orElse(null);
        BasicBlock useBlock = use.block().orElseThrow();
        if (definitionBlock == null) return false;
        if (definitionBlock != useBlock) return dominators.dominates(definitionBlock, useBlock);
        if (value.definition() instanceof PhiNode) return true;
        return value.definition() instanceof IrInstruction instruction
                && positions.getOrDefault(instruction, Integer.MAX_VALUE) < positions.getOrDefault(use, -1);
    }

    private Map<IrInstruction, Integer> positions(IrMethod method) {
        Map<IrInstruction, Integer> result = new IdentityHashMap<>();
        method.blocks().forEach(block -> {
            List<IrInstruction> instructions = new ArrayList<>(block.instructions());
            for (int index = 0; index < instructions.size(); index++) result.put(instructions.get(index), index);
        });
        return result;
    }
}
