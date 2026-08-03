package dev.frost.ir.type;

/** A declared IR value type. Analysis refinements are intentionally stored separately. */
public sealed interface IrType permits PrimitiveType, ReferenceType, ArrayType, SpecialType,
        UninitializedType, MethodType {
    /** JVM local/operand-stack slots occupied by this type. */
    int slots();

    /** A deterministic textual spelling. JVM descriptors are returned where one exists. */
    String displayName();

    default boolean isCategory2() {
        return slots() == 2;
    }

    default boolean isReferenceLike() {
        return this instanceof ReferenceType || this instanceof ArrayType
                || this == SpecialType.NULL || this instanceof UninitializedType;
    }
}
