package dev.frost.ir.type;

import java.util.Objects;

public record ReferenceType(String internalName, Nullability nullability) implements IrType {
    public static final ReferenceType OBJECT = new ReferenceType("java/lang/Object", Nullability.UNKNOWN);
    public static final ReferenceType THROWABLE = new ReferenceType("java/lang/Throwable", Nullability.NON_NULL);

    public ReferenceType {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(nullability, "nullability");
        if (internalName.isBlank() || internalName.startsWith("[") || internalName.indexOf('.') >= 0) {
            throw new IllegalArgumentException("Expected a JVM internal class name: " + internalName);
        }
    }

    @Override public int slots() { return 1; }
    @Override public String displayName() { return "L" + internalName + ";"; }

    public ReferenceType withNullability(Nullability value) {
        return value == nullability ? this : new ReferenceType(internalName, value);
    }
}
