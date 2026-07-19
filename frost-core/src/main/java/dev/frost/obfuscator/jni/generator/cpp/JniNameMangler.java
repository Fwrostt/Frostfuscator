package dev.frost.obfuscator.jni.generator.cpp;

/**
 * JNI symbol escaping helper for class and method names.
 */
public final class JniNameMangler {
    public String functionName(String ownerInternalName, String methodName) {
        return "Java_" + escape(ownerInternalName) + "_" + escape(methodName);
    }

    public String functionName(String ownerInternalName, String methodName, String descriptor) {
        return functionName(ownerInternalName, methodName) + "__" + escape(argumentDescriptor(descriptor));
    }

    public String fileName(String ownerInternalName) {
        return ownerInternalName.replace('/', '_') + ".cpp";
    }

    private String argumentDescriptor(String descriptor) {
        int start = descriptor.indexOf('(');
        int end = descriptor.indexOf(')');
        if (start < 0 || end < start) {
            return "";
        }
        return descriptor.substring(start + 1, end);
    }

    private String escape(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '/' -> builder.append('_');
                case '_' -> builder.append("_1");
                case ';' -> builder.append("_2");
                case '[' -> builder.append("_3");
                default -> {
                    if (isAsciiAlphanumeric(character)) {
                        builder.append(character);
                    } else {
                        builder.append("_0").append(String.format("%04x", (int) character));
                    }
                }
            }
        }
        return builder.toString();
    }

    private boolean isAsciiAlphanumeric(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9';
    }
}


