package dev.frost.ir.pass;

import dev.frost.ir.model.CfgEditor;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.IrMethod;
import java.util.List;
import java.util.Map;

/** Splits normal critical edges entering phi-bearing blocks. */
public final class CriticalEdgeSplittingPass implements MethodPass {
    @Override public String id() { return "frost.split-critical-edges"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        List<ControlEdge> critical = method.edges().stream().filter(edge -> !edge.kind().isExceptional())
                .filter(edge -> edge.source().normalSuccessors().size() > 1)
                .filter(edge -> edge.target().incomingEdges().stream().filter(incoming -> !incoming.kind().isExceptional()).count() > 1)
                .filter(edge -> !edge.target().phis().isEmpty()).toList();
        int index = 0;
        for (ControlEdge edge : critical) CfgEditor.splitNormalEdge(edge, "split_" + edge.id() + "_" + index++);
        if (critical.isEmpty()) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("split", (long) critical.size()));
    }
}
