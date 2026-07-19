package dev.frost.obfuscator.jni.generator.cpp;

import dev.frost.obfuscator.jni.core.descriptor.DescriptorParser;
import dev.frost.obfuscator.jni.core.descriptor.MethodDescriptor;
import dev.frost.obfuscator.jni.core.descriptor.TypeDescriptor;
import dev.frost.obfuscator.jni.core.ir.IRMethod;
import dev.frost.obfuscator.jni.core.type.TypeUtils;
import org.objectweb.asm.Opcodes;

/**
 * Emits one JNI method body from IR instructions.
 */
public final class CppMethodGenerator {
    private final DescriptorParser descriptorParser = new DescriptorParser();
    private final TypeUtils typeUtils = new TypeUtils();
    private final JniNameMangler nameMangler;
    private final CppInstructionGenerator instructionGenerator = new CppInstructionGenerator();

    public CppMethodGenerator(JniNameMangler nameMangler) {
        this.nameMangler = nameMangler;
    }

    public void appendMethod(StringBuilder source, IRMethod method, String cacheSuffix) {
        MethodDescriptor descriptor = descriptorParser.parseMethod(method.descriptor());
        appendSignature(source, method, descriptor);
        source.append("{\n");
        source.append("    frostjni::FrostFrame frame{};\n");
        appendLocalInitialization(source, method, descriptor);
        CppTranslationContext context = new CppTranslationContext(
                source,
                descriptorParser,
                typeUtils,
                new CppLabelGenerator(),
                descriptor.returnType(),
                cacheSuffix
        );
        instructionGenerator.appendInstructions(context, method.instructions());
        appendImplicitReturn(source, descriptor.returnType());
        source.append("}\n");
    }

    private void appendSignature(StringBuilder source, IRMethod method, MethodDescriptor descriptor) {
        source.append("extern \"C\" FROSTJNI_HIDDEN ")
                .append(typeUtils.toJniType(descriptor.returnType()))
                .append(" JNICALL ")
                .append(nameMangler.functionName(method.ownerInternalName(), method.name(), method.descriptor()))
                .append("(\n")
                .append("    JNIEnv* env,\n")
                .append(isStatic(method) ? "    jclass clazz" : "    jobject obj");
        for (int i = 0; i < descriptor.parameters().size(); i++) {
            source.append(",\n    ")
                    .append(typeUtils.toJniType(descriptor.parameters().get(i)))
                    .append(" arg")
                    .append(i);
        }
        source.append("\n)\n");
    }

    private void appendLocalInitialization(StringBuilder source, IRMethod method, MethodDescriptor descriptor) {
        int local = 0;
        if (!isStatic(method)) {
            source.append("    frame.locals[0].l = obj;\n");
            local = 1;
        }
        for (int i = 0; i < descriptor.parameters().size(); i++) {
            TypeDescriptor parameter = descriptor.parameters().get(i);
            source.append("    frame.locals[")
                    .append(local)
                    .append("].")
                    .append(typeUtils.toJValueMember(parameter))
                    .append(" = arg")
                    .append(i)
                    .append(";\n");
            local += parameter.isCategory2() ? 2 : 1;
        }
    }

    private void appendImplicitReturn(StringBuilder source, TypeDescriptor returnType) {
        if (returnType.isVoid()) {
            return;
        }
        String fallback = switch (returnType.valueType()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> "0";
            case LONG -> "0L";
            case FLOAT -> "0.0f";
            case DOUBLE -> "0.0";
            case ARRAY, OBJECT -> "nullptr";
            case VOID -> "";
        };
        source.append("    return ").append(fallback).append(";\n");
    }

    private boolean isStatic(IRMethod method) {
        return (method.access() & Opcodes.ACC_STATIC) != 0;
    }
}


