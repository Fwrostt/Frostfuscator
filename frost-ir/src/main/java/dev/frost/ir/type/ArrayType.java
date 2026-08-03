package dev.frost.ir.type;

import java.util.Objects;

public record ArrayType(IrType elementType, int dimensions, Nullability nullability) implements IrType {
    public ArrayType {
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(nullability, "nullability");
        if (dimensions < 1 || dimensions > 255) throw new IllegalArgumentException("dimensions must be 1..255");
        if (elementType == PrimitiveType.VOID || elementType instanceof MethodType
                || elementType instanceof SpecialType || elementType instanceof UninitializedType
                || elementType instanceof ArrayType) {
            throw new IllegalArgumentException("Invalid array element type: " + elementType);
        }
    }

    @Override public int slots() { return 1; }

    @Override public String displayName() {
        return "[".repeat(dimensions) + elementType.displayName();
    }

    public ArrayType withNullability(Nullability value) {
        return value == nullability ? this : new ArrayType(elementType, dimensions, value);
    }
}
