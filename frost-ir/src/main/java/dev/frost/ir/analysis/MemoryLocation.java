package dev.frost.ir.analysis;

import dev.frost.ir.model.Value;
import dev.frost.ir.type.IrType;
import java.util.Objects;
import java.util.OptionalLong;

/** Symbolic JVM memory locations; unknown offsets stay explicit rather than being guessed. */
public sealed interface MemoryLocation permits MemoryLocation.InstanceField,
        MemoryLocation.StaticField, MemoryLocation.ArrayElement, MemoryLocation.Unknown {

    record InstanceField(Value receiver, String owner, String name, String descriptor) implements MemoryLocation {
        public InstanceField {
            Objects.requireNonNull(receiver, "receiver");
            requireMember(owner, name, descriptor);
        }
    }

    record StaticField(String owner, String name, String descriptor) implements MemoryLocation {
        public StaticField { requireMember(owner, name, descriptor); }
    }

    record ArrayElement(Value array, OptionalLong constantIndex, IrType elementType) implements MemoryLocation {
        public ArrayElement {
            Objects.requireNonNull(array, "array");
            Objects.requireNonNull(constantIndex, "constantIndex");
            Objects.requireNonNull(elementType, "elementType");
        }
    }

    enum Unknown implements MemoryLocation { INSTANCE }

    private static void requireMember(String owner, String name, String descriptor) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (owner.isBlank() || name.isBlank() || descriptor.isBlank()) {
            throw new IllegalArgumentException("member location fields must be non-blank");
        }
    }
}
