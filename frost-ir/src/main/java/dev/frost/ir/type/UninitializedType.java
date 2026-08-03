package dev.frost.ir.type;

import dev.frost.ir.core.IrId;
import java.util.Objects;

/** Verifier identity of a value created by NEW but not yet initialized by invokespecial. */
public record UninitializedType(IrId allocationSite, ReferenceType initializedType) implements IrType {
    public UninitializedType {
        Objects.requireNonNull(allocationSite, "allocationSite");
        Objects.requireNonNull(initializedType, "initializedType");
    }

    @Override public int slots() { return 1; }
    @Override public String displayName() { return "uninitialized(" + allocationSite + ":" + initializedType.displayName() + ")"; }
}
