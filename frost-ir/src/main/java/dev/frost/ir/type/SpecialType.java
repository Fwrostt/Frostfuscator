package dev.frost.ir.type;

/** Non-source types required for verifier states, incomplete graphs, and MemorySSA. */
public enum SpecialType implements IrType {
    TOP(1, "top"),
    BOTTOM(1, "bottom"),
    NULL(1, "null"),
    UNINITIALIZED_THIS(1, "uninitialized-this"),
    RETURN_ADDRESS(1, "return-address"),
    MEMORY(0, "memory"),
    CONTROL(0, "control");

    private final int slots;
    private final String displayName;

    SpecialType(int slots, String displayName) {
        this.slots = slots;
        this.displayName = displayName;
    }

    @Override public int slots() { return slots; }
    @Override public String displayName() { return displayName; }
}
