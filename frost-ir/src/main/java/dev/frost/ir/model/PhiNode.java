package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.IrType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Edge-addressed SSA merge with simultaneous assignment semantics. */
public final class PhiNode implements ValueDefinition, ValueUser {
    private final IrMethod method;
    private final IrId id;
    private final BasicBlock block;
    private final Value result;
    private final Map<ControlEdge, Use> inputs = new LinkedHashMap<>();
    private final MetadataMap metadata;
    private int nextUseIndex;

    PhiNode(IrMethod method, IrId id, BasicBlock block, IrType type, String debugName) {
        this.method = Objects.requireNonNull(method, "method");
        this.id = Objects.requireNonNull(id, "id");
        this.block = Objects.requireNonNull(block, "block");
        metadata = new MetadataMap(method::touch);
        result = method.createValue(type, this, 0);
        result.setDebugName(debugName);
    }

    @Override public IrMethod method() { return method; }
    @Override public IrId id() { return id; }
    @Override public MetadataMap metadata() { return metadata; }
    @Override public Optional<BasicBlock> definingBlock() { return Optional.of(block); }
    public BasicBlock block() { return block; }
    public Value result() { return result; }

    public Map<ControlEdge, Value> inputs() {
        Map<ControlEdge, Value> values = new LinkedHashMap<>();
        inputs.forEach((edge, use) -> values.put(edge, use.value()));
        return Collections.unmodifiableMap(values);
    }

    public Optional<Value> input(ControlEdge edge) {
        Use use = inputs.get(edge);
        return use == null ? Optional.empty() : Optional.of(use.value());
    }

    public void putInput(ControlEdge edge, Value value) {
        method.requireOwned(edge);
        method.requireOwned(value);
        if (edge.target() != block) throw new IllegalArgumentException("Phi edge does not enter " + block);
        Use existing = inputs.get(edge);
        if (existing != null) {
            existing.replaceWith(value);
        } else {
            inputs.put(edge, new Use(this, nextUseIndex++, value));
            method.touch();
        }
    }

    public boolean removeInput(ControlEdge edge) {
        Use removed = inputs.remove(edge);
        if (removed == null) return false;
        removed.detach();
        method.touch();
        return true;
    }

    @Override public List<Use> operandUses() { return Collections.unmodifiableList(new ArrayList<>(inputs.values())); }

    void detachAllInputs() {
        inputs.values().forEach(Use::detach);
        inputs.clear();
    }
}
