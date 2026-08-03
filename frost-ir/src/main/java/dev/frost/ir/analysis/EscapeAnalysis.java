package dev.frost.ir.analysis;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Use;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.SpecialType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Intraprocedural allocation, points-to, containment, and escape-state analysis. */
public final class EscapeAnalysis {
    private final IrMethod method;
    private final List<Value> allocations = new ArrayList<>();
    private final Map<Value, Set<Value>> pointsTo = new IdentityHashMap<>();
    private final Map<Value, Boolean> closedProvenance = new IdentityHashMap<>();
    private final Map<Value, EscapeState> states = new IdentityHashMap<>();
    private final Map<Value, Set<Value>> containment = new IdentityHashMap<>();

    private EscapeAnalysis(IrMethod method) {
        this.method = method;
        initialize();
        propagatePointsTo();
        classifyEscapes();
        propagateContainmentEscapes();
    }

    public static EscapeAnalysis compute(IrMethod method) {
        return new EscapeAnalysis(Objects.requireNonNull(method, "method"));
    }

    public Set<Value> allocationSites() { return Collections.unmodifiableSet(new LinkedHashSet<>(allocations)); }

    public Set<Value> pointsTo(Value value) {
        method.requireOwned(value);
        return Collections.unmodifiableSet(new LinkedHashSet<>(pointsTo.getOrDefault(value, Set.of())));
    }

    public boolean hasClosedProvenance(Value value) {
        method.requireOwned(value);
        return closedProvenance.getOrDefault(value, false);
    }

    public EscapeState stateOfAllocation(Value allocation) {
        method.requireOwned(allocation);
        if (!states.containsKey(allocation)) throw new IllegalArgumentException("Value is not an allocation site");
        return states.get(allocation);
    }

    public EscapeState state(Value value) {
        method.requireOwned(value);
        EscapeState result = EscapeState.NO_ESCAPE;
        for (Value allocation : pointsTo.getOrDefault(value, Set.of())) result = result.merge(states.get(allocation));
        return result;
    }

    public Set<Value> containedAllocations(Value allocation) {
        method.requireOwned(allocation);
        return Collections.unmodifiableSet(new LinkedHashSet<>(containment.getOrDefault(allocation, Set.of())));
    }

    private void initialize() {
        method.parameters().forEach(parameter -> {
            pointsTo.put(parameter.value(), identitySet());
            closedProvenance.put(parameter.value(), false);
        });
        method.blocks().forEach(block -> {
            block.phis().forEach(phi -> initializeValue(phi.result()));
            block.instructions().forEach(instruction -> instruction.results().forEach(value -> {
                initializeValue(value);
                if (isAllocation(instruction)) {
                    allocations.add(value);
                    pointsTo.get(value).add(value);
                    closedProvenance.put(value, true);
                    states.put(value, EscapeState.NO_ESCAPE);
                    containment.put(value, identitySet());
                } else if (isNullConstant(instruction)) {
                    closedProvenance.put(value, true);
                }
            }));
            block.outgoingEdges().forEach(edge -> edge.values().forEach(value -> initializeValue(value.result())));
        });
    }

    private void initializeValue(Value value) {
        pointsTo.putIfAbsent(value, identitySet());
        closedProvenance.putIfAbsent(value, false);
    }

    private void propagatePointsTo() {
        boolean changed;
        do {
            changed = false;
            for (var block : method.blocks()) {
                for (PhiNode phi : block.phis()) {
                    changed |= mergeInto(phi.result(), new ArrayList<>(phi.inputs().values()));
                }
                for (IrInstruction instruction : block.instructions()) {
                    if (instruction.results().size() != 1) continue;
                    List<Value> sources = propagationSources(instruction);
                    if (!sources.isEmpty()) changed |= mergeInto(instruction.result(), sources);
                }
            }
        } while (changed);
    }

    private boolean mergeInto(Value target, List<Value> sources) {
        if (sources.isEmpty()) return false;
        boolean changed = false;
        Set<Value> targetPoints = pointsTo.get(target);
        for (Value source : sources) changed |= targetPoints.addAll(pointsTo.getOrDefault(source, Set.of()));
        boolean closed = sources.stream().allMatch(source -> closedProvenance.getOrDefault(source, false));
        if (closed && !closedProvenance.getOrDefault(target, false)) {
            closedProvenance.put(target, true);
            changed = true;
        }
        return changed;
    }

    private List<Value> propagationSources(IrInstruction instruction) {
        var code = instruction.operation().code();
        if (code.equals(CoreOps.COPY) || code.equals(CoreOps.CHECK_CAST) || code.equals(CoreOps.INITIALIZE)) {
            return instruction.operands().isEmpty() ? List.of() : List.of(instruction.operands().getFirst());
        }
        if (code.equals(CoreOps.SELECT) && instruction.operands().size() == 3) {
            return List.of(instruction.operands().get(1), instruction.operands().get(2));
        }
        return List.of();
    }

    private void classifyEscapes() {
        for (var block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                for (Use use : instruction.operandUses()) classifyUse(instruction, use);
            }
        }
    }

    private void classifyUse(IrInstruction instruction, Use use) {
        if (pointsTo.getOrDefault(use.value(), Set.of()).isEmpty()) return;
        var code = instruction.operation().code();
        int index = use.index();
        if (code.equals(CoreOps.COPY) || code.equals(CoreOps.CHECK_CAST) || code.equals(CoreOps.SELECT)
                || code.equals(CoreOps.INSTANCE_OF) || code.equals(CoreOps.LOCAL_WRITE)
                || code.equals(CoreOps.STACK_PERMUTE) || code.equals(CoreOps.MONITOR_ENTER)
                || code.equals(CoreOps.MONITOR_EXIT) || code.equals(CoreOps.FIELD_LOAD)
                || code.equals(CoreOps.ARRAY_LOAD) || code.equals(CoreOps.ARRAY_LENGTH)
                || code.equals(CoreOps.COMPARE) || code.equals(CoreOps.CONDITIONAL_BRANCH)
                || code.equals(CoreOps.SWITCH)) return;
        if (code.equals(CoreOps.INITIALIZE)) {
            if (index > 0) mark(use.value(), EscapeState.ARGUMENT_ESCAPE);
            return;
        }
        if (code.equals(CoreOps.FIELD_STORE)) {
            if (index == 1) storeInto(instruction.operands().getFirst(), use.value());
            return;
        }
        if (code.equals(CoreOps.ARRAY_STORE)) {
            if (index == 2) storeInto(instruction.operands().getFirst(), use.value());
            return;
        }
        if (code.equals(CoreOps.STATIC_STORE)) {
            mark(use.value(), EscapeState.GLOBAL_ESCAPE);
            return;
        }
        if (code.equals(CoreOps.RETURN) || code.equals(CoreOps.THROW)) {
            mark(use.value(), EscapeState.METHOD_ESCAPE);
            return;
        }
        if (code.equals(CoreOps.INVOKE) || code.equals(CoreOps.INVOKE_DYNAMIC)) {
            mark(use.value(), EscapeState.ARGUMENT_ESCAPE);
            return;
        }
        // Unknown/opaque consumers are allowed to retain the reference indefinitely.
        mark(use.value(), EscapeState.GLOBAL_ESCAPE);
    }

    private void storeInto(Value receiver, Value stored) {
        Set<Value> containers = pointsTo.getOrDefault(receiver, Set.of());
        Set<Value> contents = pointsTo.getOrDefault(stored, Set.of());
        if (!closedProvenance.getOrDefault(receiver, false) || containers.isEmpty()) {
            mark(stored, EscapeState.GLOBAL_ESCAPE);
            return;
        }
        containers.forEach(container -> containment.get(container).addAll(contents));
    }

    private void propagateContainmentEscapes() {
        boolean changed;
        do {
            changed = false;
            for (Value container : allocations) {
                EscapeState containerState = states.get(container);
                for (Value content : containment.get(container)) {
                    EscapeState merged = states.get(content).merge(containerState);
                    if (merged != states.get(content)) {
                        states.put(content, merged);
                        changed = true;
                    }
                }
            }
        } while (changed);
    }

    private void mark(Value value, EscapeState state) {
        pointsTo.getOrDefault(value, Set.of()).forEach(allocation ->
                states.put(allocation, states.get(allocation).merge(state)));
    }

    private boolean isAllocation(IrInstruction instruction) {
        return instruction.operation().code().equals(CoreOps.NEW_OBJECT)
                || instruction.operation().code().equals(CoreOps.NEW_ARRAY);
    }

    private boolean isNullConstant(IrInstruction instruction) {
        if (!instruction.operation().code().equals(CoreOps.CONSTANT) || instruction.results().size() != 1) return false;
        if (instruction.result().type() == SpecialType.NULL) return true;
        return instruction.operation().attributes().get("value") instanceof IrAttribute.StringValue text
                && text.value().equals("null");
    }

    private Set<Value> identitySet() { return Collections.newSetFromMap(new IdentityHashMap<>()); }
}
