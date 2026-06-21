package dev.frost.obfuscator.jni.core.type;

import dev.frost.obfuscator.jni.core.descriptor.TypeDescriptor;
import dev.frost.obfuscator.jni.core.descriptor.ValueType;

/**
 * Type naming helpers shared by the CLI and generator.
 */
public final class TypeUtils {
    public String toJniType(TypeDescriptor descriptor) {
        if (descriptor.valueType() == ValueType.OBJECT && "java/lang/String".equals(descriptor.internalName())) {
            return "jstring";
        }
        if (descriptor.valueType() == ValueType.ARRAY) {
            return arrayJniType(descriptor.descriptor());
        }
        return descriptor.valueType().jniType();
    }

    public String toJValueMember(TypeDescriptor descriptor) {
        return descriptor.valueType().jvalueMember();
    }

    private String arrayJniType(String descriptor) {
        if (descriptor.length() == 2 && descriptor.charAt(0) == '[') {
            return switch (descriptor.charAt(1)) {
                case 'Z' -> "jbooleanArray";
                case 'B' -> "jbyteArray";
                case 'C' -> "jcharArray";
                case 'S' -> "jshortArray";
                case 'I' -> "jintArray";
                case 'J' -> "jlongArray";
                case 'F' -> "jfloatArray";
                case 'D' -> "jdoubleArray";
                default -> "jobjectArray";
            };
        }
        return "jarray";
    }
}


