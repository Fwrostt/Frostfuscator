package dev.frost.ir.analysis;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.IrMethod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Tarjan SCC decomposition in stable method/edge order. */
public final class StronglyConnectedComponents {
    private final List<Set<BasicBlock>> components;
    private final Map<BasicBlock, Integer> componentIndex;

    private StronglyConnectedComponents(IrMethod method, EdgePolicy policy) {
        Tarjan tarjan = new Tarjan(method, policy);
        method.blocks().forEach(tarjan::visitIfNeeded);
        components = List.copyOf(tarjan.components);
        Map<BasicBlock, Integer> indices = new LinkedHashMap<>();
        for (int index = 0; index < components.size(); index++) {
            for (BasicBlock block : components.get(index)) indices.put(block, index);
        }
        componentIndex = Collections.unmodifiableMap(indices);
    }

    public static StronglyConnectedComponents compute(IrMethod method, EdgePolicy policy) {
        return new StronglyConnectedComponents(Objects.requireNonNull(method, "method"), Objects.requireNonNull(policy, "policy"));
    }

    public List<Set<BasicBlock>> components() { return components; }
    public int componentOf(BasicBlock block) { return componentIndex.getOrDefault(block, -1); }

    private static final class Tarjan {
        private final EdgePolicy policy;
        private int nextIndex;
        private final Map<BasicBlock, Integer> indices = new IdentityHashMap<>();
        private final Map<BasicBlock, Integer> lowLinks = new IdentityHashMap<>();
        private final ArrayDeque<BasicBlock> stack = new ArrayDeque<>();
        private final Set<BasicBlock> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<Set<BasicBlock>> components = new ArrayList<>();

        Tarjan(IrMethod ignored, EdgePolicy policy) { this.policy = policy; }
        void visitIfNeeded(BasicBlock block) { if (!indices.containsKey(block)) visit(block); }

        private void visit(BasicBlock block) {
            int index = nextIndex++;
            indices.put(block, index);
            lowLinks.put(block, index);
            stack.push(block);
            onStack.add(block);
            for (ControlEdge edge : block.outgoingEdges()) {
                if (!policy.test(edge)) continue;
                BasicBlock successor = edge.target();
                if (!indices.containsKey(successor)) {
                    visit(successor);
                    lowLinks.put(block, Math.min(lowLinks.get(block), lowLinks.get(successor)));
                } else if (onStack.contains(successor)) {
                    lowLinks.put(block, Math.min(lowLinks.get(block), indices.get(successor)));
                }
            }
            if (!lowLinks.get(block).equals(indices.get(block))) return;
            Set<BasicBlock> component = new LinkedHashSet<>();
            BasicBlock member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (member != block);
            components.add(Collections.unmodifiableSet(component));
        }
    }
}
