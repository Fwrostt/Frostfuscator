package dev.frost.ir.analysis;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.SpecialType;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.OptionalLong;

/** Conservative, allocation-aware alias analysis for fields, statics, and array elements. */
public final class AliasAnalysis {
    private final IrMethod method;
    private final SparseConditionalConstants constants;

    private AliasAnalysis(IrMethod method, SparseConditionalConstants constants) {
        this.method = method;
        this.constants = constants;
    }

    public static AliasAnalysis compute(IrMethod method, SparseConditionalConstants constants) {
        Objects.requireNonNull(method, "method");
        if (constants == null) constants = SparseConditionalConstants.compute(method);
        return new AliasAnalysis(method, constants);
    }

    public Optional<MemoryLocation> location(IrInstruction instruction) {
        method.requireOwned(instruction);
        var code = instruction.operation().code();
        if (code.equals(CoreOps.FIELD_LOAD) || code.equals(CoreOps.FIELD_STORE)) {
            return Optional.of(new MemoryLocation.InstanceField(instruction.operands().getFirst(),
                    string(instruction, "owner"), string(instruction, "name"), string(instruction, "descriptor")));
        }
        if (code.equals(CoreOps.STATIC_LOAD) || code.equals(CoreOps.STATIC_STORE)) {
            return Optional.of(new MemoryLocation.StaticField(string(instruction, "owner"),
                    string(instruction, "name"), string(instruction, "descriptor")));
        }
        if (code.equals(CoreOps.ARRAY_LOAD) || code.equals(CoreOps.ARRAY_STORE)) {
            Value index = instruction.operands().get(1);
            OptionalLong constantIndex = constants.constant(index)
                    .filter(known -> known.value() instanceof IrAttribute.LongValue)
                    .map(known -> OptionalLong.of(((IrAttribute.LongValue) known.value()).value()))
                    .orElseGet(OptionalLong::empty);
            IrType element = code.equals(CoreOps.ARRAY_LOAD) ? instruction.result().type()
                    : instruction.operands().get(2).type();
            return Optional.of(new MemoryLocation.ArrayElement(instruction.operands().getFirst(), constantIndex, element));
        }
        return Optional.empty();
    }

    public AliasResult alias(MemoryLocation left, MemoryLocation right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left == MemoryLocation.Unknown.INSTANCE || right == MemoryLocation.Unknown.INSTANCE) return AliasResult.MAY_ALIAS;
        if (left instanceof MemoryLocation.StaticField a && right instanceof MemoryLocation.StaticField b) {
            return sameMember(a.owner(), a.name(), a.descriptor(), b.owner(), b.name(), b.descriptor())
                    ? AliasResult.MUST_ALIAS : AliasResult.NO_ALIAS;
        }
        if (left instanceof MemoryLocation.StaticField || right instanceof MemoryLocation.StaticField) return AliasResult.NO_ALIAS;
        if (left instanceof MemoryLocation.InstanceField a && right instanceof MemoryLocation.InstanceField b) {
            if (!sameMember(a.owner(), a.name(), a.descriptor(), b.owner(), b.name(), b.descriptor())) return AliasResult.NO_ALIAS;
            return references(a.receiver(), b.receiver());
        }
        if (left instanceof MemoryLocation.ArrayElement a && right instanceof MemoryLocation.ArrayElement b) {
            AliasResult arrays = references(a.array(), b.array());
            if (arrays == AliasResult.NO_ALIAS) return arrays;
            if (a.constantIndex().isPresent() && b.constantIndex().isPresent()) {
                if (a.constantIndex().getAsLong() != b.constantIndex().getAsLong()) return AliasResult.NO_ALIAS;
                return arrays == AliasResult.MUST_ALIAS ? AliasResult.MUST_ALIAS : AliasResult.MAY_ALIAS;
            }
            return AliasResult.MAY_ALIAS;
        }
        return AliasResult.NO_ALIAS;
    }

    public boolean mayAlias(MemoryLocation left, MemoryLocation right) { return alias(left, right).mayAlias(); }

    private AliasResult references(Value left, Value right) {
        if (left == right) return AliasResult.MUST_ALIAS;
        if (isNull(left) || isNull(right)) return AliasResult.NO_ALIAS;
        if (distinctAllocations(left, right)) return AliasResult.NO_ALIAS;
        return AliasResult.MAY_ALIAS;
    }

    private boolean distinctAllocations(Value left, Value right) {
        RootInfo a = roots(left, Collections.newSetFromMap(new IdentityHashMap<>()));
        RootInfo b = roots(right, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (!a.closed || !b.closed || a.allocations.isEmpty() || b.allocations.isEmpty()) return false;
        return Collections.disjoint(a.allocations, b.allocations);
    }

    private boolean allocation(IrInstruction instruction) {
        return instruction.operation().code().equals(CoreOps.NEW_OBJECT)
                || instruction.operation().code().equals(CoreOps.NEW_ARRAY);
    }

    private RootInfo roots(Value value, Set<Value> visiting) {
        if (!visiting.add(value)) return new RootInfo(Set.of(), false);
        try {
            if (value.definition() instanceof IrInstruction instruction) {
                if (allocation(instruction)) return new RootInfo(Set.of(value), true);
                var code = instruction.operation().code();
                if ((code.equals(CoreOps.COPY) || code.equals(CoreOps.CHECK_CAST) || code.equals(CoreOps.INITIALIZE))
                        && !instruction.operands().isEmpty()) return roots(instruction.operands().getFirst(), visiting);
                if (code.equals(CoreOps.SELECT) && instruction.operands().size() == 3) {
                    return combine(List.of(roots(instruction.operands().get(1), visiting),
                            roots(instruction.operands().get(2), visiting)));
                }
            }
            if (value.definition() instanceof PhiNode phi) {
                return combine(phi.inputs().values().stream().map(input -> roots(input, visiting)).toList());
            }
            if (isNull(value)) return new RootInfo(Set.of(), true);
            return new RootInfo(Set.of(), false);
        } finally {
            visiting.remove(value);
        }
    }

    private RootInfo combine(java.util.List<RootInfo> values) {
        Set<Value> allocations = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean closed = !values.isEmpty();
        for (RootInfo value : values) {
            allocations.addAll(value.allocations);
            closed &= value.closed;
        }
        return new RootInfo(Collections.unmodifiableSet(new LinkedHashSet<>(allocations)), closed);
    }

    private boolean isNull(Value value) {
        if (value.type() == SpecialType.NULL) return true;
        return constants.constant(value).map(ConstantFact.Known::value)
                .filter(IrAttribute.StringValue.class::isInstance).map(IrAttribute.StringValue.class::cast)
                .map(text -> text.value().equals("null")).orElse(false);
    }

    private boolean sameMember(String ao, String an, String ad, String bo, String bn, String bd) {
        return ao.equals(bo) && an.equals(bn) && ad.equals(bd);
    }

    private String string(IrInstruction instruction, String name) {
        IrAttribute attribute = instruction.operation().attributes().get(name);
        if (attribute instanceof IrAttribute.StringValue text) return text.value();
        throw new IllegalArgumentException(instruction.operation().code().qualifiedName() + " lacks string attribute " + name);
    }

    private record RootInfo(Set<Value> allocations, boolean closed) {}
}
