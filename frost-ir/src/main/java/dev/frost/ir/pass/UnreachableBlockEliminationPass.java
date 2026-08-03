package dev.frost.ir.pass;

import dev.frost.ir.analysis.ControlFlow;
import dev.frost.ir.analysis.EdgePolicy;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.IrMethod;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Removes unreachable CFG subgraphs and their now-dead exception regions as one ownership transaction. */
public final class UnreachableBlockEliminationPass implements MethodPass {
    @Override public String id() { return "frost.unreachable-blocks"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        Set<BasicBlock> reachable = ControlFlow.reachable(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        Set<BasicBlock> dead = new LinkedHashSet<>(method.blocks());
        dead.removeAll(reachable);
        if (dead.isEmpty()) return PassResult.unchanged();
        method.removeBlocks(dead);
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("removedBlocks", (long) dead.size()));
    }
}
