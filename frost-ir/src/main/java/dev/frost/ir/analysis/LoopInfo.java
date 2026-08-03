package dev.frost.ir.analysis;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.IrMethod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Natural loops plus irreducible SCC regions and nesting depth. */
public final class LoopInfo {
    private final List<Loop> loops;
    private final Map<BasicBlock, Integer> depths;

    private LoopInfo(IrMethod method, EdgePolicy policy) {
        DominatorTree dominators = DominatorTree.compute(method, policy);
        Map<BasicBlock, Set<BasicBlock>> naturalByHeader = new LinkedHashMap<>();
        for (ControlEdge edge : method.edges()) {
            if (!policy.test(edge) || !dominators.dominates(edge.target(), edge.source())) continue;
            naturalByHeader.computeIfAbsent(edge.target(), ignored -> new LinkedHashSet<>())
                    .addAll(naturalLoop(edge.source(), edge.target(), policy));
        }
        List<Loop> found = new ArrayList<>();
        naturalByHeader.forEach((header, body) -> found.add(new Loop(header, body, false, entries(body, policy))));

        StronglyConnectedComponents scc = StronglyConnectedComponents.compute(method, policy);
        for (Set<BasicBlock> component : scc.components()) {
            if (!isCycle(component, policy)) continue;
            Set<BasicBlock> entryBlocks = entries(component, policy);
            if (entryBlocks.size() > 1 && found.stream().noneMatch(loop -> loop.blocks.equals(component))) {
                BasicBlock header = entryBlocks.stream().min(Comparator.comparing(BasicBlock::id)).orElseThrow();
                found.add(new Loop(header, component, true, entryBlocks));
            }
        }
        found.sort(Comparator.comparingInt((Loop loop) -> loop.blocks.size()).thenComparing(loop -> loop.header.id()));
        loops = List.copyOf(found);
        Map<BasicBlock, Integer> computedDepths = new LinkedHashMap<>();
        method.blocks().forEach(block -> computedDepths.put(block,
                (int) loops.stream().filter(loop -> loop.blocks.contains(block)).count()));
        depths = Collections.unmodifiableMap(computedDepths);
    }

    public static LoopInfo compute(IrMethod method, EdgePolicy policy) {
        return new LoopInfo(Objects.requireNonNull(method, "method"), Objects.requireNonNull(policy, "policy"));
    }

    public List<Loop> loops() { return loops; }
    public int depth(BasicBlock block) { return depths.getOrDefault(block, 0); }
    public List<Loop> containing(BasicBlock block) { return loops.stream().filter(loop -> loop.blocks.contains(block)).toList(); }

    private static Set<BasicBlock> naturalLoop(BasicBlock latch, BasicBlock header, EdgePolicy policy) {
        Set<BasicBlock> body = new LinkedHashSet<>();
        body.add(header);
        body.add(latch);
        ArrayDeque<BasicBlock> work = new ArrayDeque<>();
        if (latch != header) work.add(latch);
        while (!work.isEmpty()) {
            BasicBlock block = work.removeFirst();
            for (ControlEdge edge : block.incomingEdges()) {
                if (policy.test(edge) && body.add(edge.source())) work.addLast(edge.source());
            }
        }
        return body;
    }

    private static Set<BasicBlock> entries(Set<BasicBlock> body, EdgePolicy policy) {
        Set<BasicBlock> entries = new LinkedHashSet<>();
        for (BasicBlock block : body) {
            if (block.incomingEdges().stream().filter(policy).map(ControlEdge::source).anyMatch(source -> !body.contains(source))) {
                entries.add(block);
            }
        }
        return Collections.unmodifiableSet(entries);
    }

    private static boolean isCycle(Set<BasicBlock> component, EdgePolicy policy) {
        if (component.size() > 1) return true;
        BasicBlock only = component.iterator().next();
        return only.outgoingEdges().stream().filter(policy).anyMatch(edge -> edge.target() == only);
    }

    public record Loop(BasicBlock header, Set<BasicBlock> blocks, boolean irreducible,
                       Set<BasicBlock> entries) {
        public Loop {
            Objects.requireNonNull(header, "header");
            blocks = Collections.unmodifiableSet(new LinkedHashSet<>(blocks));
            entries = Collections.unmodifiableSet(new LinkedHashSet<>(entries));
            if (!blocks.contains(header)) throw new IllegalArgumentException("loop header is outside loop body");
        }
    }
}
