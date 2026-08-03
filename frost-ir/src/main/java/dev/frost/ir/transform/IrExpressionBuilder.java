package dev.frost.ir.transform;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.IrType;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small scheduling helper for typed SSA expression rewrites. Every emitted value has a concrete
 * insertion point, so obfuscation passes can deliberately spread one expression across blocks.
 */
public final class IrExpressionBuilder {
    private final IrMethod method;
    private final BasicBlock block;
    private int insertionIndex;

    public IrExpressionBuilder(IrMethod method, BasicBlock block, int insertionIndex) {
        this.method = Objects.requireNonNull(method, "method");
        this.block = Objects.requireNonNull(block, "block");
        method.requireOwned(block);
        if (insertionIndex < 0 || insertionIndex > block.instructions().size()) {
            throw new IndexOutOfBoundsException(insertionIndex);
        }
        if (insertionIndex == block.instructions().size()
                && block.terminator().isPresent()) {
            throw new IllegalArgumentException("Cannot insert after a block terminator");
        }
        this.insertionIndex = insertionIndex;
    }

    public Value constant(long value, IrType type) {
        return emit(new Operation(CoreOps.CONSTANT, Map.of("value", IrAttribute.of(value))),
                List.of(), type);
    }

    public Value unary(OperationCode code, Value operand, IrType resultType) {
        return emit(new Operation(code), List.of(operand), resultType);
    }

    public Value binary(OperationCode code, Value left, Value right, IrType resultType) {
        return emit(new Operation(code), List.of(left, right), resultType);
    }

    public Value emit(Operation operation, List<Value> operands, IrType resultType) {
        IrInstruction instruction = method.createInstruction(operation, operands, List.of(resultType));
        block.insert(insertionIndex++, instruction);
        return instruction.result();
    }

    public BasicBlock block() {
        return block;
    }

    public int insertionIndex() {
        return insertionIndex;
    }
}
