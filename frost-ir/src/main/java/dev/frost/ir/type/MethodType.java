package dev.frost.ir.type;

import java.util.List;
import java.util.Objects;

public record MethodType(List<IrType> parameterTypes, IrType returnType) implements IrType {
    public MethodType {
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes"));
        returnType = Objects.requireNonNull(returnType, "returnType");
        if (parameterTypes.stream().anyMatch(type -> type == PrimitiveType.VOID || type instanceof MethodType)) {
            throw new IllegalArgumentException("Invalid method parameter type");
        }
        if (returnType instanceof MethodType || returnType instanceof SpecialType
                || returnType instanceof UninitializedType) {
            throw new IllegalArgumentException("Invalid method return type: " + returnType);
        }
    }

    @Override public int slots() { return 1; }

    @Override public String displayName() {
        StringBuilder out = new StringBuilder("(");
        parameterTypes.forEach(type -> out.append(type.displayName()));
        return out.append(')').append(returnType.displayName()).toString();
    }
}
