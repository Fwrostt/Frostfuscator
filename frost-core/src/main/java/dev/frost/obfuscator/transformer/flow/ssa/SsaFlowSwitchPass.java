package dev.frost.obfuscator.transformer.flow.ssa;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import dev.frost.ir.type.PrimitiveType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Phase 4.2: condition-to-switch dispatch plus keyed rewriting of existing switch selectors. */
public final class SsaFlowSwitchPass implements MethodPass {
    private final int probability;

    public SsaFlowSwitchPass(int probability) {
        this.probability = Math.max(0, Math.min(100, probability));
    }

    @Override public String id() { return "frost.flow.switch-ssa"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (probability == 0) return PassResult.unchanged();
        SplittableRandom random = context.randomFor(id());
        long converted = 0, hashed = 0;
        for (BasicBlock block : List.copyOf(method.blocks())) {
            IrInstruction terminator = block.terminator().orElse(null);
            if (terminator == null || random.nextInt(100) >= probability
                    || block.normalSuccessors().stream().anyMatch(edge -> !edge.values().isEmpty())) continue;
            if (terminator.operation().code().equals(CoreOps.CONDITIONAL_BRANCH)
                    && rewriteConditional(method, block, terminator)) {
                converted++;
            } else if (terminator.operation().code().equals(CoreOps.SWITCH)
                    && hashSwitch(method, block, terminator, nonZero(random))) {
                hashed++;
            }
        }
        if (converted == 0 && hashed == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(),
                Map.of("convertedConditions", converted, "hashedSwitches", hashed));
    }

    private boolean rewriteConditional(IrMethod method, BasicBlock block, IrInstruction terminator) {
        String condition = terminator.operation().attributes().get("condition") instanceof dev.frost.ir.model.IrAttribute.StringValue text
                ? text.value() : "";
        if (!condition.equals("IFEQ") && !condition.equals("IFNE")
                && !condition.equals("IF_ICMPEQ") && !condition.equals("IF_ICMPNE")) return false;
        if (terminator.operands().stream().anyMatch(value -> !(value.type() instanceof PrimitiveType primitive)
                || primitive.computationalType() != PrimitiveType.INT)) return false;

        ControlEdge onTrue = edge(block, EdgeKind.TRUE);
        ControlEdge onFalse = edge(block, EdgeKind.FALSE);
        EdgeSnapshot trueSnapshot = snapshot(onTrue);
        EdgeSnapshot falseSnapshot = snapshot(onFalse);
        Value selector;
        if (terminator.operands().size() == 1) {
            selector = terminator.operands().getFirst();
        } else if (terminator.operands().size() == 2) {
            IrInstruction difference = SsaFlowSupport.instruction(method, CoreOps.XOR,
                    terminator.operands(), PrimitiveType.INT, Map.of());
            block.insert(block.instructions().size() - 1, difference);
            selector = difference.result();
        } else {
            return false;
        }

        boolean zeroMeansTrue = condition.equals("IFEQ") || condition.equals("IF_ICMPEQ");
        EdgeSnapshot zero = zeroMeansTrue ? trueSnapshot : falseSnapshot;
        EdgeSnapshot otherwise = zeroMeansTrue ? falseSnapshot : trueSnapshot;
        ControlEdge caseEdge = method.connect(block, zero.edge().target(), EdgeKind.SWITCH_CASE,
                "0", null, zero.edge().priority());
        zero.edge().metadata().copyPersistentTo(caseEdge.metadata());
        zero.phiInputs().forEach((phi, value) -> phi.putInput(caseEdge, value));
        ControlEdge defaultEdge = method.connect(block, otherwise.edge().target(), EdgeKind.SWITCH_DEFAULT,
                "default", null, otherwise.edge().priority());
        otherwise.edge().metadata().copyPersistentTo(defaultEdge.metadata());
        otherwise.phiInputs().forEach((phi, value) -> phi.putInput(defaultEdge, value));
        method.disconnect(onTrue);
        method.disconnect(onFalse);
        terminator.erase();
        IrInstruction replacement = method.createInstruction(new Operation(CoreOps.SWITCH), List.of(selector), List.of());
        terminator.metadata().copyPersistentTo(replacement.metadata());
        block.append(replacement);
        return true;
    }

    private boolean hashSwitch(IrMethod method, BasicBlock block, IrInstruction terminator, int seed) {
        if (terminator.operands().size() != 1) return false;
        List<EdgeSnapshot> snapshots = block.normalSuccessors().stream().map(this::snapshot).toList();
        ControlEdge defaultOriginal = snapshots.stream().map(EdgeSnapshot::edge)
                .filter(edge -> edge.kind() == EdgeKind.SWITCH_DEFAULT).findFirst().orElse(null);
        if (defaultOriginal == null) return false;

        IrInstruction key = SsaFlowSupport.constant(method, seed, PrimitiveType.INT);
        IrInstruction encoded = SsaFlowSupport.instruction(method, CoreOps.XOR,
                List.of(terminator.operands().getFirst(), key.result()), PrimitiveType.INT, Map.of());
        int insertion = block.instructions().size() - 1;
        block.insert(insertion++, key);
        block.insert(insertion, encoded);

        for (EdgeSnapshot snapshot : snapshots) {
            ControlEdge edge = snapshot.edge();
            String label = edge.kind() == EdgeKind.SWITCH_CASE
                    ? Integer.toString(Integer.parseInt(edge.label()) ^ seed) : edge.label();
            ControlEdge replacement = method.connect(block, edge.target(), edge.kind(), label,
                    edge.catchType().orElse(null), edge.priority());
            edge.metadata().copyPersistentTo(replacement.metadata());
            snapshot.phiInputs().forEach((phi, value) -> phi.putInput(replacement, value));
        }
        snapshots.forEach(snapshot -> method.disconnect(snapshot.edge()));
        terminator.erase();
        IrInstruction replacement = method.createInstruction(new Operation(CoreOps.SWITCH),
                List.of(encoded.result()), List.of());
        terminator.metadata().copyPersistentTo(replacement.metadata());
        block.append(replacement);
        return true;
    }

    private EdgeSnapshot snapshot(ControlEdge edge) {
        Map<PhiNode, Value> inputs = new LinkedHashMap<>();
        edge.target().phis().forEach(phi -> phi.input(edge).ifPresent(value -> inputs.put(phi, value)));
        return new EdgeSnapshot(edge, inputs);
    }

    private ControlEdge edge(BasicBlock block, EdgeKind kind) {
        return block.outgoingEdges().stream().filter(edge -> edge.kind() == kind).findFirst().orElseThrow();
    }

    private int nonZero(SplittableRandom random) {
        int value;
        do value = random.nextInt(); while (value == 0);
        return value;
    }

    private record EdgeSnapshot(ControlEdge edge, Map<PhiNode, Value> phiInputs) {
        private EdgeSnapshot {
            phiInputs = Collections.unmodifiableMap(new LinkedHashMap<>(phiInputs));
        }
    }
}
