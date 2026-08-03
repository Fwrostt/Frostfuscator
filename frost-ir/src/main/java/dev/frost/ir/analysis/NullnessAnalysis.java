package dev.frost.ir.analysis;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeValue;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.ArrayType;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.ReferenceType;
import dev.frost.ir.type.SpecialType;
import dev.frost.ir.type.UninitializedType;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Flow-sensitive SSA nullness facts with a finite four-element lattice. */
public final class NullnessAnalysis {
    private final IrMethod method;
    private final Map<Value, Nullness> facts = new IdentityHashMap<>();

    private NullnessAnalysis(IrMethod method) {
        this.method = method;
        initialize();
        solve();
    }

    public static NullnessAnalysis compute(IrMethod method) {
        return new NullnessAnalysis(Objects.requireNonNull(method, "method"));
    }

    public Optional<Nullness> fact(Value value) {
        method.requireOwned(value);
        return Optional.ofNullable(facts.get(value));
    }

    public boolean isDefinitelyNull(Value value) { return fact(value).orElse(Nullness.MAYBE_NULL) == Nullness.NULL; }
    public boolean isDefinitelyNonNull(Value value) { return fact(value).orElse(Nullness.MAYBE_NULL) == Nullness.NON_NULL; }

    private void initialize() {
        method.parameters().forEach(parameter -> putDeclared(parameter.value()));
        method.blocks().forEach(block -> {
            block.phis().forEach(phi -> { if (referenceLike(phi.result().type())) facts.put(phi.result(), Nullness.BOTTOM); });
            block.instructions().forEach(instruction -> instruction.results().forEach(value -> {
                if (referenceLike(value.type())) facts.put(value, Nullness.BOTTOM);
            }));
            block.outgoingEdges().forEach(edge -> edge.values().forEach(value -> {
                if (referenceLike(value.result().type())) facts.put(value.result(), Nullness.NON_NULL);
            }));
        });
    }

    private void solve() {
        boolean changed;
        do {
            changed = false;
            for (var block : method.blocks()) {
                for (PhiNode phi : block.phis()) {
                    if (!facts.containsKey(phi.result())) continue;
                    Nullness candidate = Nullness.BOTTOM;
                    for (Value input : phi.inputs().values()) candidate = candidate.join(value(input));
                    changed |= update(phi.result(), candidate);
                }
                for (IrInstruction instruction : block.instructions()) {
                    if (instruction.results().size() != 1 || !facts.containsKey(instruction.result())) continue;
                    changed |= update(instruction.result(), evaluate(instruction));
                }
            }
        } while (changed);
    }

    private Nullness evaluate(IrInstruction instruction) {
        var code = instruction.operation().code();
        if (code.equals(CoreOps.NEW_OBJECT) || code.equals(CoreOps.NEW_ARRAY) || code.equals(CoreOps.INITIALIZE)) {
            return Nullness.NON_NULL;
        }
        if (code.equals(CoreOps.CONSTANT)
                && instruction.operation().attributes().get("value") instanceof IrAttribute.StringValue text
                && text.value().equals("null")) return Nullness.NULL;
        if ((code.equals(CoreOps.COPY) || code.equals(CoreOps.CHECK_CAST)) && !instruction.operands().isEmpty()) {
            return value(instruction.operands().getFirst());
        }
        if (code.equals(CoreOps.SELECT) && instruction.operands().size() == 3) {
            return value(instruction.operands().get(1)).join(value(instruction.operands().get(2)));
        }
        return declared(instruction.result().type());
    }

    private Nullness value(Value value) { return facts.getOrDefault(value, Nullness.MAYBE_NULL); }

    private boolean update(Value value, Nullness candidate) {
        Nullness previous = facts.get(value);
        Nullness merged = previous.join(candidate);
        if (merged == previous) return false;
        facts.put(value, merged);
        return true;
    }

    private void putDeclared(Value value) {
        if (referenceLike(value.type())) facts.put(value, declared(value.type()));
    }

    private Nullness declared(IrType type) {
        if (type == SpecialType.NULL) return Nullness.NULL;
        if (type instanceof UninitializedType || type == SpecialType.UNINITIALIZED_THIS) return Nullness.NON_NULL;
        Nullability nullability = type instanceof ReferenceType reference ? reference.nullability()
                : type instanceof ArrayType array ? array.nullability() : Nullability.UNKNOWN;
        return nullability == Nullability.NON_NULL ? Nullness.NON_NULL : Nullness.MAYBE_NULL;
    }

    private boolean referenceLike(IrType type) {
        return type instanceof ReferenceType || type instanceof ArrayType || type instanceof UninitializedType
                || type == SpecialType.NULL || type == SpecialType.UNINITIALIZED_THIS;
    }
}
