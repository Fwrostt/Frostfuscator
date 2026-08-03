package dev.frost.ir.bytecode;

import dev.frost.ir.model.Value;
import dev.frost.ir.type.IrType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable JVM local/operand-stack state at an exact IR/bytecode program point. */
public record JvmFrameState(List<Slot> locals, List<Value> stack) {
    public JvmFrameState {
        locals = List.copyOf(Objects.requireNonNull(locals, "locals"));
        stack = List.copyOf(Objects.requireNonNull(stack, "stack"));
    }

    public record Slot(IrType type, Value value) {
        public Slot { Objects.requireNonNull(type, "type"); }
        public Optional<Value> valueOptional() { return Optional.ofNullable(value); }
    }
}
