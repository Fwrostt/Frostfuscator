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

/** Deterministic fixed-point dominators, immediate dominators, tree children, and frontiers. */
public final class DominatorTree {
    private final IrMethod method;
    private final EdgePolicy policy;
    private final Set<BasicBlock> reachable;
    private final Map<BasicBlock, Set<BasicBlock>> dominators;
    private final Map<BasicBlock, BasicBlock> immediateDominators;
    private final Map<BasicBlock, List<BasicBlock>> children;
    private final Map<BasicBlock, Set<BasicBlock>> frontiers;

    private DominatorTree(IrMethod method, EdgePolicy policy) {
        this.method = method;
        this.policy = policy;
        reachable = ControlFlow.reachable(method, policy);
        dominators = computeDominators();
        immediateDominators = computeImmediateDominators();
        children = computeChildren();
        frontiers = computeFrontiers();
    }

    public static DominatorTree compute(IrMethod method, EdgePolicy policy) {
        return new DominatorTree(Objects.requireNonNull(method, "method"), Objects.requireNonNull(policy, "policy"));
    }

    public IrMethod method() { return method; }
    public EdgePolicy edgePolicy() { return policy; }
    public Set<BasicBlock> reachableBlocks() { return reachable; }
    public boolean isReachable(BasicBlock block) { return reachable.contains(block); }

    public boolean dominates(BasicBlock dominator, BasicBlock block) {
        method.requireOwned(dominator);
        method.requireOwned(block);
        return dominators.getOrDefault(block, Set.of()).contains(dominator);
    }

    public boolean strictlyDominates(BasicBlock dominator, BasicBlock block) {
        return dominator != block && dominates(dominator, block);
    }

    public Set<BasicBlock> dominatorsOf(BasicBlock block) {
        method.requireOwned(block);
        return dominators.getOrDefault(block, Set.of());
    }

    public Optional<BasicBlock> immediateDominator(BasicBlock block) {
        method.requireOwned(block);
        return Optional.ofNullable(immediateDominators.get(block));
    }

    public List<BasicBlock> children(BasicBlock block) {
        method.requireOwned(block);
        return children.getOrDefault(block, List.of());
    }

    public Set<BasicBlock> frontier(BasicBlock block) {
        method.requireOwned(block);
        return frontiers.getOrDefault(block, Set.of());
    }

    public Optional<BasicBlock> nearestCommonDominator(BasicBlock left, BasicBlock right) {
        method.requireOwned(left);
        method.requireOwned(right);
        Set<BasicBlock> common = new LinkedHashSet<>(dominatorsOf(left));
        common.retainAll(dominatorsOf(right));
        return common.stream().max(Comparator.comparingInt(block -> dominatorsOf(block).size()));
    }

    private Map<BasicBlock, Set<BasicBlock>> computeDominators() {
        Map<BasicBlock, Set<BasicBlock>> result = new LinkedHashMap<>();
        if (method.entryBlock().isEmpty()) return Map.of();
        BasicBlock entry = method.entryBlock().orElseThrow();
        for (BasicBlock block : method.blocks()) {
            if (!reachable.contains(block)) result.put(block, Set.of(block));
            else if (block == entry) result.put(block, Set.of(block));
            else result.put(block, new LinkedHashSet<>(reachable));
        }

        boolean changed;
        do {
            changed = false;
            for (BasicBlock block : method.blocks()) {
                if (block == entry || !reachable.contains(block)) continue;
                List<BasicBlock> predecessors = block.incomingEdges().stream().filter(policy)
                        .map(ControlEdge::source).filter(reachable::contains).distinct().toList();
                Set<BasicBlock> updated = new LinkedHashSet<>();
                if (!predecessors.isEmpty()) {
                    updated.addAll(result.get(predecessors.getFirst()));
                    for (int index = 1; index < predecessors.size(); index++) {
                        updated.retainAll(result.get(predecessors.get(index)));
                    }
                }
                updated.add(block);
                if (!updated.equals(result.get(block))) {
                    result.put(block, updated);
                    changed = true;
                }
            }
        } while (changed);

        result.replaceAll((ignored, set) -> Collections.unmodifiableSet(new LinkedHashSet<>(set)));
        return Collections.unmodifiableMap(result);
    }

    private Map<BasicBlock, BasicBlock> computeImmediateDominators() {
        Map<BasicBlock, BasicBlock> result = new LinkedHashMap<>();
        BasicBlock entry = method.entryBlock().orElse(null);
        for (BasicBlock block : method.blocks()) {
            if (block == entry || !reachable.contains(block)) continue;
            dominators.get(block).stream().filter(candidate -> candidate != block)
                    .max(Comparator.comparingInt(candidate -> dominators.get(candidate).size()))
                    .ifPresent(value -> result.put(block, value));
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<BasicBlock, List<BasicBlock>> computeChildren() {
        Map<BasicBlock, List<BasicBlock>> result = new LinkedHashMap<>();
        method.blocks().forEach(block -> result.put(block, new ArrayList<>()));
        immediateDominators.forEach((block, parent) -> result.get(parent).add(block));
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(result);
    }

    private Map<BasicBlock, Set<BasicBlock>> computeFrontiers() {
        Map<BasicBlock, Set<BasicBlock>> result = new LinkedHashMap<>();
        method.blocks().forEach(block -> result.put(block, new LinkedHashSet<>()));
        for (BasicBlock block : method.blocks()) {
            if (!reachable.contains(block)) continue;
            List<BasicBlock> predecessors = block.incomingEdges().stream().filter(policy)
                    .map(ControlEdge::source).filter(reachable::contains).distinct().toList();
            if (predecessors.size() < 2) continue;
            BasicBlock idom = immediateDominators.get(block);
            for (BasicBlock predecessor : predecessors) {
                BasicBlock runner = predecessor;
                while (runner != null && runner != idom) {
                    result.get(runner).add(block);
                    runner = immediateDominators.get(runner);
                }
            }
        }
        result.replaceAll((ignored, values) -> Collections.unmodifiableSet(new LinkedHashSet<>(values)));
        return Collections.unmodifiableMap(result);
    }
}
