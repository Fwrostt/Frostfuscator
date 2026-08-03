package dev.frost.ir.bytecode;

import dev.frost.ir.type.ArrayType;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.MethodType;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.ReferenceType;
import java.util.Arrays;
import org.objectweb.asm.Type;

public final class JvmTypeAdapter {
    private JvmTypeAdapter() {}

    public static IrType fromAsm(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> PrimitiveType.VOID;
            case Type.BOOLEAN -> PrimitiveType.BOOLEAN;
            case Type.CHAR -> PrimitiveType.CHAR;
            case Type.BYTE -> PrimitiveType.BYTE;
            case Type.SHORT -> PrimitiveType.SHORT;
            case Type.INT -> PrimitiveType.INT;
            case Type.FLOAT -> PrimitiveType.FLOAT;
            case Type.LONG -> PrimitiveType.LONG;
            case Type.DOUBLE -> PrimitiveType.DOUBLE;
            case Type.OBJECT -> new ReferenceType(type.getInternalName(), Nullability.UNKNOWN);
            case Type.ARRAY -> new ArrayType(fromAsm(type.getElementType()), type.getDimensions(), Nullability.UNKNOWN);
            case Type.METHOD -> methodType(type.getDescriptor());
            default -> throw new IllegalArgumentException("Unsupported ASM type sort " + type.getSort());
        };
    }

    public static MethodType methodType(String descriptor) {
        Type type = Type.getMethodType(descriptor);
        return new MethodType(Arrays.stream(type.getArgumentTypes()).map(JvmTypeAdapter::fromAsm).toList(),
                fromAsm(type.getReturnType()));
    }
}
