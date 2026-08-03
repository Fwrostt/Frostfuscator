package dev.frost.ir.pass;

import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.OperationTrait;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.ValueDefinition;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mark/sweep SSA DCE rooted at terminators and observable operations. */
public final class DeadCodeEliminationPass implements MethodPass {
    @Override public String id() { return "frost.dead-code"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        Set<ValueDefinition> live = new LinkedHashSet<>();
        ArrayDeque<ValueDefinition> work = new ArrayDeque<>();
        method.blocks().forEach(block -> block.instructions().stream()
                .filter(instruction -> !removable(instruction))
                .forEach(instruction -> mark(instruction, live, work)));

        while (!work.isEmpty()) {
            ValueDefinition definition = work.removeFirst();
            if (definition instanceof IrInstruction instruction) {
                instruction.operands().forEach(value -> mark(value.definition(), live, work));
            } else if (definition instanceof PhiNode phi) {
                phi.inputs().values().forEach(value -> mark(value.definition(), live, work));
            }
        }

        Set<IrInstruction> deadInstructions = new LinkedHashSet<>();
        Set<PhiNode> deadPhis = new LinkedHashSet<>();
        method.blocks().forEach(block -> {
            block.instructions().stream().filter(instruction -> !live.contains(instruction))
                    .forEach(deadInstructions::add);
            block.phis().stream().filter(phi -> !live.contains(phi)).forEach(deadPhis::add);
        });
        int removed = deadInstructions.size() + deadPhis.size();
        if (removed == 0) return PassResult.unchanged();
        method.removeDefinitions(deadInstructions, deadPhis);
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("removed", (long) removed));
    }

    private void mark(ValueDefinition definition, Set<ValueDefinition> live, ArrayDeque<ValueDefinition> work) {
        if (definition instanceof IrInstruction || definition instanceof PhiNode) {
            if (live.add(definition)) work.addLast(definition);
        }
    }

    private boolean removable(IrInstruction instruction) {
        if (instruction.isTerminator() || !instruction.effects().isPure()) return false;
        return instruction.method().context().schema(instruction.operation().code())
                .map(schema -> schema.hasTrait(OperationTrait.SPECULATABLE)).orElse(false);
    }
}
