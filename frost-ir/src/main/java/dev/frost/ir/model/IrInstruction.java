package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.IrType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Scheduled operation with stable operand uses and zero or more SSA results. */
public final class IrInstruction implements ValueDefinition, ValueUser {
    private final IrMethod method;
    private final IrId id;
    private Operation operation;
    private final List<Use> operands;
    private final List<Value> results;
    private final MetadataMap metadata;
    private BasicBlock block;

    IrInstruction(IrMethod method, IrId id, Operation operation, List<Value> operands, List<IrType> resultTypes) {
        this.method = Objects.requireNonNull(method, "method");
        this.id = Objects.requireNonNull(id, "id");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.operands = new ArrayList<>(operands.size());
        for (int index = 0; index < operands.size(); index++) {
            this.operands.add(new Use(this, index, operands.get(index)));
        }
        this.results = new ArrayList<>(resultTypes.size());
        for (int index = 0; index < resultTypes.size(); index++) {
            this.results.add(method.createValue(resultTypes.get(index), this, index));
        }
        metadata = new MetadataMap(method::touch);
    }

    @Override public IrMethod method() { return method; }
    @Override public IrId id() { return id; }
    @Override public MetadataMap metadata() { return metadata; }
    @Override public Optional<BasicBlock> definingBlock() { return Optional.ofNullable(block); }
    public Optional<BasicBlock> block() { return Optional.ofNullable(block); }
    public Operation operation() { return operation; }
    public List<Value> operands() { return operands.stream().map(Use::value).toList(); }
    @Override public List<Use> operandUses() { return Collections.unmodifiableList(operands); }
    public List<Value> results() { return Collections.unmodifiableList(results); }
    public Value result() {
        if (results.size() != 1) throw new IllegalStateException("Instruction has " + results.size() + " results");
        return results.getFirst();
    }

    public void setOperation(Operation replacement) {
        Objects.requireNonNull(replacement, "replacement");
        method.requireOperationShape(replacement.code(), operands.size(), results.size());
        if (!operation.equals(replacement)) {
            operation = replacement;
            method.touch();
        }
    }

    public void setOperand(int index, Value replacement) { operands.get(index).replaceWith(replacement); }

    public boolean isTerminator() {
        return method.context().schema(operation.code())
                .map(schema -> schema.hasTrait(OperationTrait.TERMINATOR)).orElse(false);
    }

    public EffectSummary effects() {
        return method.context().schema(operation.code()).orElseThrow().effects();
    }

    public void erase() {
        if (block == null) throw new IllegalStateException("Instruction is not attached to a block");
        block.remove(this);
    }

    void attach(BasicBlock owner) {
        if (block != null) throw new IllegalStateException("Instruction already belongs to " + block);
        block = owner;
    }

    void detach() {
        if (block == null) throw new IllegalStateException("Instruction is not attached");
        block = null;
        operands.forEach(Use::detach);
    }

    void detachOperandsForBulkRemoval() { operands.forEach(Use::detach); }

    void detachFromBlockAfterBulkRemoval() {
        if (block == null) throw new IllegalStateException("Instruction is not attached");
        block = null;
    }

    void moveTo(BasicBlock target) {
        if (block == null) throw new IllegalStateException("Instruction is not attached");
        method.requireOwned(target);
        block = target;
    }

    boolean hasLiveResults() { return results.stream().anyMatch(Value::isUsed); }

    @Override public String toString() { return operation.code().qualifiedName() + "@" + id; }
}
