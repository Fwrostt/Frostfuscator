package dev.frost.ir.analysis;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Edge-correct SSA liveness; phi operands are uses on predecessor edges. */
public final class Liveness {
    private final IrMethod method;
    private final Map<BasicBlock, Set<Value>> liveIn;
    private final Map<BasicBlock, Set<Value>> liveOut;
    private final Map<ControlEdge, Set<Value>> phiUses;
    private final Map<IrInstruction, Set<Value>> liveAfter;

    private Liveness(IrMethod method, EdgePolicy policy) {
        this.method = method;
        Map<BasicBlock, Set<Value>> definitions = new LinkedHashMap<>();
        Map<BasicBlock, Set<Value>> upwardUses = new LinkedHashMap<>();
        Map<BasicBlock, Set<Value>> phiDefinitions = new LinkedHashMap<>();
        Map<ControlEdge, Set<Value>> edgePhiUses = new LinkedHashMap<>();
        for (ControlEdge edge : method.edges()) edgePhiUses.put(edge, new LinkedHashSet<>());

        for (BasicBlock block : method.blocks()) {
            Set<Value> defs = new LinkedHashSet<>();
            Set<Value> uses = new LinkedHashSet<>();
            Set<Value> phiDefs = new LinkedHashSet<>();
            for (PhiNode phi : block.phis()) {
                defs.add(phi.result());
                phiDefs.add(phi.result());
                phi.inputs().forEach((edge, value) -> edgePhiUses.computeIfAbsent(edge, ignored -> new LinkedHashSet<>()).add(value));
            }
            for (IrInstruction instruction : block.instructions()) {
                for (Value operand : instruction.operands()) if (!defs.contains(operand)) uses.add(operand);
                defs.addAll(instruction.results());
            }
            definitions.put(block, defs);
            upwardUses.put(block, uses);
            phiDefinitions.put(block, phiDefs);
        }

        Map<BasicBlock, Set<Value>> in = new LinkedHashMap<>();
        Map<BasicBlock, Set<Value>> out = new LinkedHashMap<>();
        method.blocks().forEach(block -> { in.put(block, new LinkedHashSet<>()); out.put(block, new LinkedHashSet<>()); });
        boolean changed;
        do {
            changed = false;
            List<BasicBlock> reverse = new ArrayList<>(method.blocks());
            Collections.reverse(reverse);
            for (BasicBlock block : reverse) {
                Set<Value> newOut = new LinkedHashSet<>();
                for (ControlEdge edge : block.outgoingEdges()) {
                    if (!policy.test(edge)) continue;
                    Set<Value> successorIn = new LinkedHashSet<>(in.get(edge.target()));
                    successorIn.removeAll(phiDefinitions.get(edge.target()));
                    newOut.addAll(successorIn);
                    newOut.addAll(edgePhiUses.getOrDefault(edge, Set.of()));
                }
                Set<Value> newIn = new LinkedHashSet<>(newOut);
                newIn.removeAll(definitions.get(block));
                newIn.addAll(upwardUses.get(block));
                if (!newOut.equals(out.get(block))) { out.put(block, newOut); changed = true; }
                if (!newIn.equals(in.get(block))) { in.put(block, newIn); changed = true; }
            }
        } while (changed);

        liveIn = freeze(in);
        liveOut = freeze(out);
        phiUses = freeze(edgePhiUses);
        Map<IrInstruction, Set<Value>> after = new IdentityHashMap<>();
        for (BasicBlock block : method.blocks()) {
            Set<Value> live = new LinkedHashSet<>(out.get(block));
            List<IrInstruction> reverse = new ArrayList<>(block.instructions());
            Collections.reverse(reverse);
            for (IrInstruction instruction : reverse) {
                after.put(instruction, Collections.unmodifiableSet(new LinkedHashSet<>(live)));
                live.removeAll(instruction.results());
                live.addAll(instruction.operands());
            }
        }
        liveAfter = Collections.unmodifiableMap(after);
    }

    public static Liveness compute(IrMethod method, EdgePolicy policy) {
        return new Liveness(Objects.requireNonNull(method, "method"), Objects.requireNonNull(policy, "policy"));
    }

    public Set<Value> liveIn(BasicBlock block) { method.requireOwned(block); return liveIn.getOrDefault(block, Set.of()); }
    public Set<Value> liveOut(BasicBlock block) { method.requireOwned(block); return liveOut.getOrDefault(block, Set.of()); }
    public Set<Value> phiUses(ControlEdge edge) { method.requireOwned(edge); return phiUses.getOrDefault(edge, Set.of()); }
    public Set<Value> liveAfter(IrInstruction instruction) { method.requireOwned(instruction); return liveAfter.getOrDefault(instruction, Set.of()); }
    public boolean isLiveAfter(Value value, IrInstruction instruction) { return liveAfter(instruction).contains(value); }

    private static <K> Map<K, Set<Value>> freeze(Map<K, Set<Value>> source) {
        Map<K, Set<Value>> result = new LinkedHashMap<>();
        source.forEach((key, values) -> result.put(key, Collections.unmodifiableSet(new LinkedHashSet<>(values))));
        return Collections.unmodifiableMap(result);
    }
}
