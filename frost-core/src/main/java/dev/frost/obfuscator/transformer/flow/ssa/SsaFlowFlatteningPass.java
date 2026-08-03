package dev.frost.obfuscator.transformer.flow.ssa;

import dev.frost.ir.analysis.ControlFlow;
import dev.frost.ir.analysis.EdgePolicy;
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
import dev.frost.ir.type.ArrayType;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.ReferenceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** Phase 4.4: edge-state dispatcher flattening with explicit SSA payload phis. */
public final class SsaFlowFlatteningPass implements MethodPass {
    private final int minimumBlocks;
    private final int maximumBlocks;
    private final int maximumExceptionHandlers;
    private final int fakeStates;

    public SsaFlowFlatteningPass(int minimumBlocks, int maximumBlocks,
                                int maximumExceptionHandlers, int fakeStates) {
        this.minimumBlocks = Math.max(2, minimumBlocks);
        this.maximumBlocks = Math.max(this.minimumBlocks, maximumBlocks);
        this.maximumExceptionHandlers = Math.max(0, maximumExceptionHandlers);
        this.fakeStates = Math.max(0, fakeStates);
    }

    @Override public String id() { return "frost.flow.flatten-ssa"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (method.blocks().size() < minimumBlocks || method.blocks().size() > maximumBlocks
                || method.exceptionRegions().size() > maximumExceptionHandlers) return PassResult.unchanged();
        Set<BasicBlock> reachable = ControlFlow.reachable(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        List<ControlEdge> originals = method.edges().stream()
                .filter(edge -> !edge.kind().isExceptional() && reachable.contains(edge.source())
                        && reachable.contains(edge.target())).toList();
        if (originals.size() < 2 || originals.stream().anyMatch(edge -> !edge.values().isEmpty())) {
            return PassResult.unchanged();
        }
        Set<BasicBlock> handlers = new LinkedHashSet<>();
        method.edges().stream().filter(edge -> edge.kind().isExceptional()).map(ControlEdge::target).forEach(handlers::add);
        if (originals.stream().anyMatch(edge -> handlers.contains(edge.target()))) return PassResult.unchanged();

        List<EdgeSnapshot> snapshots = originals.stream().map(this::snapshot).toList();
        LinkedHashSet<PhiNode> targetPhis = new LinkedHashSet<>();
        snapshots.forEach(snapshot -> targetPhis.addAll(snapshot.phiInputs().keySet()));
        if (targetPhis.stream().map(phi -> phi.result().type()).anyMatch(type -> !supportsFiller(type))) {
            return PassResult.unchanged();
        }

        BasicBlock entry = method.entryBlock().orElseThrow();
        IrInstruction entryTerminator = entry.terminator().orElseThrow();
        Map<IrType, Value> fillers = new LinkedHashMap<>();
        int insertion = entry.instructions().indexOf(entryTerminator);
        for (PhiNode phi : targetPhis) {
            IrType type = phi.result().type();
            if (fillers.containsKey(type)) continue;
            IrInstruction filler = filler(method, type);
            entry.insert(insertion++, filler);
            fillers.put(type, filler.result());
        }

        SplittableRandom random = context.randomFor(id());
        Set<Integer> usedStates = new LinkedHashSet<>();
        BasicBlock dispatcher = method.createBlock(SsaFlowSupport.uniqueBlockName(method, "flatten$dispatch"));
        PhiNode statePhi = dispatcher.addPhi(PrimitiveType.INT, "flatten$state");
        Map<PhiNode, PhiNode> payloads = new LinkedHashMap<>();
        for (PhiNode targetPhi : targetPhis) {
            payloads.put(targetPhi, dispatcher.addPhi(targetPhi.result().type(),
                    "flatten$payload$" + targetPhi.id().value()));
        }

        List<Transition> transitions = new ArrayList<>();
        for (int index = 0; index < snapshots.size(); index++) {
            EdgeSnapshot snapshot = snapshots.get(index);
            int state = uniqueState(random, usedStates);
            BasicBlock transition = method.createBlock(SsaFlowSupport.uniqueBlockName(method,
                    "flatten$set$" + index));
            IrInstruction stateValue = SsaFlowSupport.constant(method, state, PrimitiveType.INT);
            transition.append(stateValue);
            transition.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
            ControlEdge sourceEdge = method.connect(snapshot.edge().source(), transition, snapshot.edge().kind(),
                    snapshot.edge().label(), null, snapshot.edge().priority());
            snapshot.edge().metadata().copyPersistentTo(sourceEdge.metadata());
            ControlEdge dispatchEdge = method.connect(transition, dispatcher, EdgeKind.NORMAL,
                    "dispatch", null, 0);
            statePhi.putInput(dispatchEdge, stateValue.result());
            for (PhiNode targetPhi : targetPhis) {
                Value input = snapshot.phiInputs().getOrDefault(targetPhi, fillers.get(targetPhi.result().type()));
                payloads.get(targetPhi).putInput(dispatchEdge, input);
            }

            BasicBlock proxy = method.createBlock(SsaFlowSupport.uniqueBlockName(method,
                    "flatten$case$" + index));
            proxy.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
            method.connect(dispatcher, proxy, EdgeKind.SWITCH_CASE, Integer.toString(state), null, 0);
            ControlEdge targetEdge = method.connect(proxy, snapshot.edge().target(), EdgeKind.NORMAL,
                    "flattened", null, snapshot.edge().priority());
            snapshot.edge().metadata().copyPersistentTo(targetEdge.metadata());
            snapshot.phiInputs().forEach((phi, ignored) -> phi.putInput(targetEdge, payloads.get(phi).result()));
            transitions.add(new Transition(snapshot, dispatchEdge));
        }

        BasicBlock invalid = method.createBlock(SsaFlowSupport.uniqueBlockName(method, "flatten$invalid"));
        invalid.append(method.createInstruction(CoreOps.UNREACHABLE, List.of(), List.of()));
        method.connect(dispatcher, invalid, EdgeKind.SWITCH_DEFAULT, "default", null, 0);
        for (int index = 0; index < fakeStates; index++) {
            int state = uniqueState(random, usedStates);
            BasicBlock fake = method.createBlock(SsaFlowSupport.uniqueBlockName(method, "flatten$fake$" + index));
            fake.append(method.createInstruction(CoreOps.UNREACHABLE, List.of(), List.of()));
            method.connect(dispatcher, fake, EdgeKind.SWITCH_CASE, Integer.toString(state), null, 0);
        }
        dispatcher.append(method.createInstruction(new Operation(CoreOps.SWITCH),
                List.of(statePhi.result()), List.of()));

        snapshots.forEach(snapshot -> method.disconnect(snapshot.edge()));
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of(
                "flattenedMethods", 1L,
                "dispatcherEdges", (long) transitions.size(),
                "fakeStates", (long) fakeStates));
    }

    private boolean supportsFiller(IrType type) {
        return type instanceof PrimitiveType primitive && primitive != PrimitiveType.VOID
                || type instanceof ReferenceType || type instanceof ArrayType;
    }

    private IrInstruction filler(IrMethod method, IrType type) {
        if (type instanceof PrimitiveType primitive) return SsaFlowSupport.constant(method, 0, primitive);
        return SsaFlowSupport.nullConstant(method);
    }

    private EdgeSnapshot snapshot(ControlEdge edge) {
        Map<PhiNode, Value> inputs = new LinkedHashMap<>();
        edge.target().phis().forEach(phi -> phi.input(edge).ifPresent(value -> inputs.put(phi, value)));
        return new EdgeSnapshot(edge, inputs);
    }

    private int uniqueState(SplittableRandom random, Set<Integer> used) {
        int value;
        do value = random.nextInt(); while (!used.add(value));
        return value;
    }

    private record EdgeSnapshot(ControlEdge edge, Map<PhiNode, Value> phiInputs) {
        private EdgeSnapshot {
            phiInputs = Collections.unmodifiableMap(new LinkedHashMap<>(phiInputs));
        }
    }
    private record Transition(EdgeSnapshot original, ControlEdge dispatcherEdge) {}
}
