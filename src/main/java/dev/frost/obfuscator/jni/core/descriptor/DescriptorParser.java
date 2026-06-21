package dev.frost.obfuscator.jni.core.descriptor;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for JVM descriptors used by FrostJNI backends.
 */
public final class DescriptorParser {
    public MethodDescriptor parseMethod(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || descriptor.charAt(0) != '(') {
            throw new IllegalArgumentException("Invalid method descriptor: " + descriptor);
        }

        List<TypeDescriptor> parameters = new ArrayList<>();
        int index = 1;
        while (descriptor.charAt(index) != ')') {
            ParsedType parsed = parseType(descriptor, index);
            parameters.add(parsed.type);
            index = parsed.nextIndex;
        }

        ParsedType returnType = parseType(descriptor, index + 1);
        if (returnType.nextIndex != descriptor.length()) {
            throw new IllegalArgumentException("Trailing descriptor data: " + descriptor);
        }
        return new MethodDescriptor(parameters, returnType.type);
    }

    public TypeDescriptor parseField(String descriptor) {
        ParsedType parsed = parseType(descriptor, 0);
        if (parsed.nextIndex != descriptor.length()) {
            throw new IllegalArgumentException("Trailing descriptor data: " + descriptor);
        }
        return parsed.type;
    }

    private ParsedType parseType(String descriptor, int index) {
        if (index >= descriptor.length()) {
            throw new IllegalArgumentException("Unexpected end of descriptor: " + descriptor);
        }

        char marker = descriptor.charAt(index);
        return switch (marker) {
            case 'V' -> new ParsedType(new TypeDescriptor("V", ValueType.VOID, null), index + 1);
            case 'Z' -> new ParsedType(new TypeDescriptor("Z", ValueType.BOOLEAN, null), index + 1);
            case 'B' -> new ParsedType(new TypeDescriptor("B", ValueType.BYTE, null), index + 1);
            case 'C' -> new ParsedType(new TypeDescriptor("C", ValueType.CHAR, null), index + 1);
            case 'S' -> new ParsedType(new TypeDescriptor("S", ValueType.SHORT, null), index + 1);
            case 'I' -> new ParsedType(new TypeDescriptor("I", ValueType.INT, null), index + 1);
            case 'J' -> new ParsedType(new TypeDescriptor("J", ValueType.LONG, null), index + 1);
            case 'F' -> new ParsedType(new TypeDescriptor("F", ValueType.FLOAT, null), index + 1);
            case 'D' -> new ParsedType(new TypeDescriptor("D", ValueType.DOUBLE, null), index + 1);
            case 'L' -> parseObject(descriptor, index);
            case '[' -> parseArray(descriptor, index);
            default -> throw new UnsupportedOperationException("Unsupported descriptor type '" + marker + "' in " + descriptor);
        };
    }

    private ParsedType parseArray(String descriptor, int index) {
        int cursor = index;
        while (cursor < descriptor.length() && descriptor.charAt(cursor) == '[') {
            cursor++;
        }
        ParsedType component = parseType(descriptor, cursor);
        return new ParsedType(new TypeDescriptor(descriptor.substring(index, component.nextIndex), ValueType.ARRAY, null), component.nextIndex);
    }

    private ParsedType parseObject(String descriptor, int index) {
        int end = descriptor.indexOf(';', index);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated object descriptor: " + descriptor);
        }
        String raw = descriptor.substring(index, end + 1);
        String internalName = descriptor.substring(index + 1, end);
        return new ParsedType(new TypeDescriptor(raw, ValueType.OBJECT, internalName), end + 1);
    }

    private record ParsedType(TypeDescriptor type, int nextIndex) {
    }
}


