package dev.frost.obfuscator.transformer.flow.ssa;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Phase 4.1: changes only physical block layout; CFG and edge-keyed phi identity remain intact. */
public final class SsaControlFlowShufflingPass implements MethodPass {
    @Override public String id() { return "frost.flow.shuffle-ssa"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (method.blocks().size() <= 2) return PassResult.unchanged();
        BasicBlock entry = method.entryBlock().orElseThrow();
        List<BasicBlock> tail = new ArrayList<>(method.blocks().stream().filter(block -> block != entry).toList());
        List<BasicBlock> original = List.copyOf(tail);
        SplittableRandom random = context.randomFor(id());
        for (int index = tail.size() - 1; index > 0; index--) {
            int swap = random.nextInt(index + 1);
            BasicBlock value = tail.get(index);
            tail.set(index, tail.get(swap));
            tail.set(swap, value);
        }
        if (tail.equals(original)) {
            BasicBlock first = tail.removeFirst();
            tail.add(first);
        }
        List<BasicBlock> order = new ArrayList<>(tail.size() + 1);
        order.add(entry);
        order.addAll(tail);
        method.reorderBlocks(order);
        return new PassResult(true, PreservedAnalyses.all(), List.of(), Map.of("shuffledBlocks", (long) tail.size()));
    }
}
