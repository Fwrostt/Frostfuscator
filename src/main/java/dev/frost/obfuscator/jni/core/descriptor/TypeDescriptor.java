package dev.frost.obfuscator.jni.core.descriptor;

import java.util.Objects;

/**
 * Parsed JVM type descriptor.
 */
public record TypeDescriptor(String descriptor, ValueType valueType, String internalName) {
    public TypeDescriptor {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(valueType, "valueType");
    }

    public boolean isVoid() {
        return valueType == ValueType.VOID;
    }

    public boolean isInt() {
        return valueType == ValueType.INT;
    }

    public boolean isPrimitiveNumeric() {
        return switch (valueType) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    public boolean isCategory2() {
        return valueType.category2();
    }

    public boolean isObject() {
        return valueType == ValueType.OBJECT;
    }

    public boolean isArray() {
        return valueType == ValueType.ARRAY;
    }
}


