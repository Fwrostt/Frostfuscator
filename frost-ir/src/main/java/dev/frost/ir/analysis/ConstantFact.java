package dev.frost.ir.analysis;

import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.type.IrType;
import java.util.Objects;

public sealed interface ConstantFact permits ConstantFact.Undefined, ConstantFact.Known, ConstantFact.Overdefined {
    record Undefined() implements ConstantFact {}
    record Overdefined() implements ConstantFact {}
    record Known(IrType type, IrAttribute value) implements ConstantFact {
        public Known { Objects.requireNonNull(type, "type"); Objects.requireNonNull(value, "value"); }
    }

    ConstantFact UNDEFINED = new Undefined();
    ConstantFact OVERDEFINED = new Overdefined();
}
