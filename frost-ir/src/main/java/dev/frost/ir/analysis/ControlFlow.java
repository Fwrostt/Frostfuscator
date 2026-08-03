package dev.frost.ir.analysis;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.IrMethod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ControlFlow {
    private ControlFlow() {}

    public static Set<BasicBlock> reachable(IrMethod method, EdgePolicy policy) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(policy, "policy");
        if (method.entryBlock().isEmpty()) return Set.of();
        Set<BasicBlock> seen = new LinkedHashSet<>();
        ArrayDeque<BasicBlock> work = new ArrayDeque<>();
        work.add(method.entryBlock().orElseThrow());
        while (!work.isEmpty()) {
            BasicBlock block = work.removeFirst();
            if (!seen.add(block)) continue;
            block.outgoingEdges().stream().filter(policy).map(ControlEdge::target).forEach(work::addLast);
        }
        return Collections.unmodifiableSet(seen);
    }

    public static List<BasicBlock> reversePostOrder(IrMethod method, EdgePolicy policy) {
        if (method.entryBlock().isEmpty()) return List.of();
        Set<BasicBlock> seen = new LinkedHashSet<>();
        List<BasicBlock> postorder = new ArrayList<>();
        visit(method.entryBlock().orElseThrow(), policy, seen, postorder);
        Collections.reverse(postorder);
        return List.copyOf(postorder);
    }

    private static void visit(BasicBlock block, EdgePolicy policy, Set<BasicBlock> seen,
                              List<BasicBlock> postorder) {
        if (!seen.add(block)) return;
        for (ControlEdge edge : block.outgoingEdges()) {
            if (policy.test(edge)) visit(edge.target(), policy, seen, postorder);
        }
        postorder.add(block);
    }
}
