package dev.frost.obfuscator.jni.generator.cpp;

import dev.frost.obfuscator.jni.core.descriptor.DescriptorParser;
import dev.frost.obfuscator.jni.core.descriptor.TypeDescriptor;
import dev.frost.obfuscator.jni.core.type.TypeUtils;

/**
 * Mutable state shared while translating a single C++ method body.
 */
public final class CppTranslationContext {
    private final StringBuilder source;
    private final DescriptorParser descriptorParser;
    private final TypeUtils typeUtils;
    private final CppLabelGenerator labelGenerator;
    private final TypeDescriptor returnType;
    private final String cacheSuffix;
    private int tempCounter;

    public CppTranslationContext(
            StringBuilder source,
            DescriptorParser descriptorParser,
            TypeUtils typeUtils,
            CppLabelGenerator labelGenerator,
            TypeDescriptor returnType,
            String cacheSuffix
    ) {
        this.source = source;
        this.descriptorParser = descriptorParser;
        this.typeUtils = typeUtils;
        this.labelGenerator = labelGenerator;
        this.returnType = returnType;
        this.cacheSuffix = cacheSuffix;
    }

    public DescriptorParser descriptorParser() {
        return descriptorParser;
    }

    public TypeUtils typeUtils() {
        return typeUtils;
    }

    public CppLabelGenerator labelGenerator() {
        return labelGenerator;
    }

    public TypeDescriptor returnType() {
        return returnType;
    }

    public String classCache() {
        return "frostClassCache_" + cacheSuffix;
    }

    public String methodCache() {
        return "frostMethodCache_" + cacheSuffix;
    }

    public String fieldCache() {
        return "frostFieldCache_" + cacheSuffix;
    }

    public String nextTemp(String prefix) {
        return prefix + tempCounter++;
    }

    public void line(String line) {
        source.append("    ").append(line).append('\n');
    }

    public void nestedLine(String line) {
        source.append("        ").append(line).append('\n');
    }

    public void label(String label) {
        source.append(label).append(":\n");
        source.append("    ;\n");
    }

    public void raw(String text) {
        source.append(text);
    }

    public String jvalueMember(TypeDescriptor descriptor) {
        return typeUtils.toJValueMember(descriptor);
    }

    public String jniType(TypeDescriptor descriptor) {
        return typeUtils.toJniType(descriptor);
    }

    public String stackValue(TypeDescriptor descriptor, String slotExpression) {
        String value = slotExpression + "." + jvalueMember(descriptor);
        return switch (descriptor.valueType()) {
            case ARRAY, OBJECT -> {
                String type = jniType(descriptor);
                yield "jobject".equals(type) ? value : "static_cast<" + type + ">(" + value + ")";
            }
            default -> value;
        };
    }

    public String fallbackReturn() {
        return switch (returnType.valueType()) {
            case VOID -> "return;";
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> "return 0;";
            case LONG -> "return 0L;";
            case FLOAT -> "return 0.0f;";
            case DOUBLE -> "return 0.0;";
            case ARRAY, OBJECT -> "return nullptr;";
        };
    }
}


