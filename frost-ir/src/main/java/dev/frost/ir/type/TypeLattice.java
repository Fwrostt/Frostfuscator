package dev.frost.ir.type;

/** Pluggable class hierarchy operations used by verifier, phi, alias, and lowering analyses. */
public interface TypeLattice {
    IrType join(IrType left, IrType right);

    boolean isAssignable(IrType from, IrType to);

    static TypeLattice conservative() {
        return ConservativeTypeLattice.INSTANCE;
    }
}
