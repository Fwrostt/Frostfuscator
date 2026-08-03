package dev.frost.ir.analysis;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.IrMethod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Post-dominance with an implicit virtual exit for multiple return/throw/unreachable blocks. */
public final class PostDominatorTree {
    private final IrMethod method;
    private final EdgePolicy policy;
    private final Set<BasicBlock> participating;
    private final Map<BasicBlock, Set<BasicBlock>> postDominators;
    private final Map<BasicBlock, BasicBlock> immediatePostDominators;

    private PostDominatorTree(IrMethod method, EdgePolicy policy) {
        this.method = method;
        this.policy = policy;
        participating = blocksReachingAnExit();
        postDominators = computeSets();
        immediatePostDominators = computeImmediate();
    }

    public static PostDominatorTree compute(IrMethod method, EdgePolicy policy) {
        return new PostDominatorTree(Objects.requireNonNull(method, "method"), Objects.requireNonNull(policy, "policy"));
    }

    public boolean postDominates(BasicBlock candidate, BasicBlock block) {
        method.requireOwned(candidate);
        method.requireOwned(block);
        return postDominators.getOrDefault(block, Set.of()).contains(candidate);
    }

    public Set<BasicBlock> postDominatorsOf(BasicBlock block) {
        method.requireOwned(block);
        return postDominators.getOrDefault(block, Set.of());
    }

    public Optional<BasicBlock> immediatePostDominator(BasicBlock block) {
        method.requireOwned(block);
        return Optional.ofNullable(immediatePostDominators.get(block));
    }

    public Set<BasicBlock> participatingBlocks() { return participating; }

    private Set<BasicBlock> blocksReachingAnExit() {
        Set<BasicBlock> exits = new LinkedHashSet<>();
        for (BasicBlock block : method.blocks()) {
            boolean hasSuccessor = block.outgoingEdges().stream().anyMatch(policy);
            if (!hasSuccessor) exits.add(block);
        }
        Set<BasicBlock> seen = new LinkedHashSet<>(exits);
        java.util.ArrayDeque<BasicBlock> work = new java.util.ArrayDeque<>(exits);
        while (!work.isEmpty()) {
            BasicBlock current = work.removeFirst();
            current.incomingEdges().stream().filter(policy).map(ControlEdge::source).forEach(predecessor -> {
                if (seen.add(predecessor)) work.addLast(predecessor);
            });
        }
        return Collections.unmodifiableSet(seen);
    }

    private Map<BasicBlock, Set<BasicBlock>> computeSets() {
        Map<BasicBlock, Set<BasicBlock>> result = new LinkedHashMap<>();
        for (BasicBlock block : method.blocks()) {
            if (!participating.contains(block)) result.put(block, Set.of(block));
            else if (block.outgoingEdges().stream().noneMatch(policy)) result.put(block, Set.of(block));
            else result.put(block, new LinkedHashSet<>(participating));
        }
        boolean changed;
        do {
            changed = false;
            List<BasicBlock> reverse = new ArrayList<>(method.blocks());
            Collections.reverse(reverse);
            for (BasicBlock block : reverse) {
                if (!participating.contains(block)) continue;
                List<BasicBlock> successors = block.outgoingEdges().stream().filter(policy)
                        .map(ControlEdge::target).filter(participating::contains).distinct().toList();
                if (successors.isEmpty()) continue;
                Set<BasicBlock> updated = new LinkedHashSet<>(result.get(successors.getFirst()));
                for (int index = 1; index < successors.size(); index++) updated.retainAll(result.get(successors.get(index)));
                updated.add(block);
                if (!updated.equals(result.get(block))) { result.put(block, updated); changed = true; }
            }
        } while (changed);
        result.replaceAll((ignored, value) -> Collections.unmodifiableSet(new LinkedHashSet<>(value)));
        return Collections.unmodifiableMap(result);
    }

    private Map<BasicBlock, BasicBlock> computeImmediate() {
        Map<BasicBlock, BasicBlock> result = new LinkedHashMap<>();
        for (BasicBlock block : method.blocks()) {
            postDominators.getOrDefault(block, Set.of()).stream().filter(candidate -> candidate != block)
                    .max(Comparator.comparingInt(candidate -> postDominators.get(candidate).size()))
                    .ifPresent(value -> result.put(block, value));
        }
        return Collections.unmodifiableMap(result);
    }
}
