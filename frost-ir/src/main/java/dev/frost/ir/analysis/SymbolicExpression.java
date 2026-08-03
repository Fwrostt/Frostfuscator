package dev.frost.ir.analysis;

import dev.frost.ir.core.IrId;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.Operation;
import dev.frost.ir.type.IrType;
import java.util.List;
import java.util.Objects;

/** Immutable, identity-free expression keys used by value numbering and rewrite engines. */
public sealed interface SymbolicExpression permits SymbolicExpression.Leaf,
        SymbolicExpression.Constant, SymbolicExpression.Apply {

    record Leaf(LeafKind kind, IrId source, IrType type) implements SymbolicExpression {
        public Leaf {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(type, "type");
        }
    }

    record Constant(IrType type, IrAttribute value) implements SymbolicExpression {
        public Constant {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }
    }

    record Apply(Operation operation, IrType resultType, int resultIndex,
                 List<Integer> operandValueNumbers) implements SymbolicExpression {
        public Apply {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(resultType, "resultType");
            if (resultIndex < 0) throw new IllegalArgumentException("resultIndex must be non-negative");
            operandValueNumbers = List.copyOf(Objects.requireNonNull(operandValueNumbers, "operandValueNumbers"));
        }
    }

    enum LeafKind { PARAMETER, PHI, EDGE, EFFECTFUL, UNKNOWN }
}
