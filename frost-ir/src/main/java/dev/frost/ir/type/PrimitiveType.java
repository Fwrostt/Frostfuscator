package dev.frost.ir.type;

public enum PrimitiveType implements IrType {
    BOOLEAN("Z", 1), BYTE("B", 1), CHAR("C", 1), SHORT("S", 1), INT("I", 1),
    FLOAT("F", 1), LONG("J", 2), DOUBLE("D", 2), VOID("V", 0);

    private final String descriptor;
    private final int slots;

    PrimitiveType(String descriptor, int slots) {
        this.descriptor = descriptor;
        this.slots = slots;
    }

    @Override public int slots() { return slots; }
    @Override public String displayName() { return descriptor; }

    /** The verifier represents all JVM int-family values as int on the operand stack. */
    public PrimitiveType computationalType() {
        return switch (this) {
            case BOOLEAN, BYTE, CHAR, SHORT -> INT;
            default -> this;
        };
    }
}
