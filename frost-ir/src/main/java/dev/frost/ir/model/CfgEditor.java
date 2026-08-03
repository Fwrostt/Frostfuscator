package dev.frost.ir.model;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ownership-safe CFG surgery utilities that preserve edge-keyed phi semantics. */
public final class CfgEditor {
    private CfgEditor() {}

    public static SplitEdge splitNormalEdge(ControlEdge edge, String blockName) {
        Objects.requireNonNull(edge, "edge");
        if (edge.kind().isExceptional()) throw new IllegalArgumentException("Exceptional edges require EH-aware splitting");
        IrMethod method = edge.method();
        method.requireOwned(edge);
        BasicBlock source = edge.source(), target = edge.target();
        Map<PhiNode, Value> inputs = new LinkedHashMap<>();
        target.phis().forEach(phi -> phi.input(edge).ifPresent(value -> inputs.put(phi, value)));

        try (IrMethod.Mutation ignored = method.beginMutation("split-edge")) {
            BasicBlock middle = method.createBlock(blockName);
            middle.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
            ControlEdge first = method.connect(source, middle, edge.kind(), edge.label(), null, edge.priority());
            edge.metadata().copyPersistentTo(first.metadata());
            Map<Value, Value> transferred = new IdentityHashMap<>();
            for (EdgeValue value : edge.values()) {
                EdgeValue replacement = first.addValue(value.role(), value.result().type());
                value.metadata().copyPersistentTo(replacement.metadata());
                value.result().metadata().copyPersistentTo(replacement.result().metadata());
                replacement.result().setDebugName(value.result().debugName());
                transferred.put(value.result(), replacement.result());
            }
            method.disconnect(edge);
            ControlEdge second = method.connect(middle, target, EdgeKind.NORMAL, "split", null, 0);
            inputs.forEach((phi, value) -> phi.putInput(second, transferred.getOrDefault(value, value)));
            return new SplitEdge(middle, first, second);
        }
    }

    public record SplitEdge(BasicBlock block, ControlEdge first, ControlEdge second) {}
}
