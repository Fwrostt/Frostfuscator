package dev.frost.ir.type;

import java.util.Objects;

final class ConservativeTypeLattice implements TypeLattice {
    static final ConservativeTypeLattice INSTANCE = new ConservativeTypeLattice();

    @Override
    public IrType join(IrType left, IrType right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.equals(right)) return left;
        if (left == SpecialType.BOTTOM) return right;
        if (right == SpecialType.BOTTOM) return left;
        if (left == SpecialType.TOP || right == SpecialType.TOP) return SpecialType.TOP;
        if (left == SpecialType.NULL && right.isReferenceLike()) return nullable(right);
        if (right == SpecialType.NULL && left.isReferenceLike()) return nullable(left);
        if (left instanceof PrimitiveType lp && right instanceof PrimitiveType rp
                && lp.computationalType() == rp.computationalType()) return lp.computationalType();
        if (left instanceof ReferenceType lr && right instanceof ReferenceType rr
                && lr.internalName().equals(rr.internalName())) {
            return lr.withNullability(joinNullability(lr.nullability(), rr.nullability()));
        }
        if (left instanceof ArrayType la && right instanceof ArrayType ra
                && la.elementType().equals(ra.elementType()) && la.dimensions() == ra.dimensions()) {
            return la.withNullability(joinNullability(la.nullability(), ra.nullability()));
        }
        if (left.isReferenceLike() && right.isReferenceLike()) return ReferenceType.OBJECT;
        return SpecialType.TOP;
    }

    @Override
    public boolean isAssignable(IrType from, IrType to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to) || from == SpecialType.BOTTOM || to == SpecialType.TOP) return true;
        if (from == SpecialType.NULL) return to.isReferenceLike();
        if (from instanceof PrimitiveType fp && to instanceof PrimitiveType tp) {
            return fp.computationalType() == tp.computationalType();
        }
        if (from instanceof ReferenceType source && to instanceof ReferenceType target
                && source.internalName().equals(target.internalName())) {
            return assignableNullability(source.nullability(), target.nullability());
        }
        if (from instanceof ArrayType source && to instanceof ArrayType target
                && source.dimensions() == target.dimensions()
                && source.elementType().equals(target.elementType())) {
            return assignableNullability(source.nullability(), target.nullability());
        }
        // The core lattice has no class-hierarchy resolver. Treat reference-to-reference
        // assignment as provisionally legal and leave exact hierarchy rejection to the JVM
        // verifier used by transactional lowering.
        if (from.isReferenceLike() && to.isReferenceLike()) return true;
        return false;
    }

    private Nullability joinNullability(Nullability left, Nullability right) {
        if (left == right) return left;
        if (left == Nullability.NULLABLE || right == Nullability.NULLABLE) return Nullability.NULLABLE;
        return Nullability.UNKNOWN;
    }

    private boolean assignableNullability(Nullability from, Nullability to) {
        return from == to || to == Nullability.UNKNOWN || to == Nullability.NULLABLE;
    }

    private IrType nullable(IrType type) {
        if (type instanceof ReferenceType ref) return ref.withNullability(Nullability.NULLABLE);
        if (type instanceof ArrayType array) return array.withNullability(Nullability.NULLABLE);
        if (type instanceof UninitializedType) return SpecialType.TOP;
        return type;
    }
}
