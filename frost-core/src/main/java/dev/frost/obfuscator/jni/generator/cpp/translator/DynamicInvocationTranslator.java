package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.descriptor.MethodDescriptor;
import dev.frost.obfuscator.jni.core.descriptor.TypeDescriptor;
import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.core.model.DynamicInvocationReference;
import dev.frost.obfuscator.jni.generator.cpp.CppStringEscaper;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers supported invokedynamic bootstrap patterns.
 */
public final class DynamicInvocationTranslator implements InstructionTranslator {
    public static boolean isSupported(DynamicInvocationReference reference) {
        return reference.isStringConcatFactory();
    }

    @Override
    public boolean supports(IROpcode opcode) {
        return opcode == IROpcode.INVOKEDYNAMIC;
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        DynamicInvocationReference reference = (DynamicInvocationReference) instruction.operands().get(0);
        if (isSupported(reference)) {
            translateStringConcat(context, reference);
            return;
        }
        context.line("frostjni::ExceptionUtils::throwUnsupportedOperationException(env, "
                + CppStringEscaper.quote("invokedynamic is not implemented yet: " + reference.name() + reference.descriptor()) + ");");
        context.line(context.fallbackReturn());
    }

    private void translateStringConcat(CppTranslationContext context, DynamicInvocationReference reference) {
        MethodDescriptor descriptor = context.descriptorParser().parseMethod(reference.descriptor());
        if (!descriptor.returnType().isObject() || !"java/lang/String".equals(descriptor.returnType().internalName())) {
            emitUnsupported(context, reference, "non-String concat result");
            return;
        }

        context.line("{");
        List<String> arguments = popArguments(context, descriptor.parameters());
        String builderClass = context.nextTemp("builderClass");
        String builder = context.nextTemp("builder");
        context.nestedLine("jclass " + builderClass + " = " + context.classCache() + ".get(env, \"java/lang/StringBuilder\");");
        context.nestedLine("jmethodID builderCtor = " + context.methodCache()
                + ".get(env, " + builderClass + ", \"java/lang/StringBuilder\", \"<init>\", \"()V\", false);");
        context.nestedLine("jobject " + builder + " = env->NewObject(" + builderClass + ", builderCtor);");

        if ("makeConcatWithConstants".equals(reference.bootstrapName())) {
            appendRecipe(context, reference, builderClass, builder, arguments);
        } else {
            for (int i = 0; i < arguments.size(); i++) {
                appendValue(context, builderClass, builder, descriptor.parameters().get(i), arguments.get(i));
            }
        }

        context.nestedLine("jmethodID toStringMethod = " + context.methodCache()
                + ".get(env, " + builderClass + ", \"java/lang/StringBuilder\", \"toString\", \"()Ljava/lang/String;\", false);");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result.l = env->CallObjectMethod(" + builder + ", toStringMethod);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private List<String> popArguments(CppTranslationContext context, List<TypeDescriptor> parameters) {
        List<String> arguments = new ArrayList<>();
        for (int i = parameters.size() - 1; i >= 0; i--) {
            TypeDescriptor parameter = parameters.get(i);
            String name = context.nextTemp("concatArg");
            context.nestedLine(context.jniType(parameter) + " " + name + " = "
                    + context.stackValue(parameter, "frame.stack[--frame.sp]") + ";");
            arguments.add(0, name);
        }
        return arguments;
    }

    private void appendRecipe(
            CppTranslationContext context,
            DynamicInvocationReference reference,
            String builderClass,
            String builder,
            List<String> arguments
    ) {
        String recipe = reference.bootstrapArguments().isEmpty() ? "" : String.valueOf(reference.bootstrapArguments().get(0));
        MethodDescriptor descriptor = context.descriptorParser().parseMethod(reference.descriptor());
        int argumentIndex = 0;
        int constantIndex = 1;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < recipe.length(); i++) {
            char marker = recipe.charAt(i);
            if (marker == '\u0001') {
                appendLiteral(context, builderClass, builder, literal.toString());
                literal.setLength(0);
                if (argumentIndex < arguments.size()) {
                    appendValue(context, builderClass, builder, descriptor.parameters().get(argumentIndex), arguments.get(argumentIndex));
                    argumentIndex++;
                }
            } else if (marker == '\u0002') {
                appendLiteral(context, builderClass, builder, literal.toString());
                literal.setLength(0);
                if (constantIndex < reference.bootstrapArguments().size()) {
                    appendLiteral(context, builderClass, builder, String.valueOf(reference.bootstrapArguments().get(constantIndex)));
                    constantIndex++;
                }
            } else {
                literal.append(marker);
            }
        }
        appendLiteral(context, builderClass, builder, literal.toString());
    }

    private void appendLiteral(CppTranslationContext context, String builderClass, String builder, String literal) {
        if (literal.isEmpty()) {
            return;
        }
        String value = context.nextTemp("concatLiteral");
        context.nestedLine("jstring " + value + " = env->NewStringUTF(" + CppStringEscaper.quote(literal) + ");");
        appendWithDescriptor(context, builderClass, builder, "(Ljava/lang/String;)Ljava/lang/StringBuilder;", value);
        context.nestedLine("env->DeleteLocalRef(" + value + ");");
    }

    private void appendValue(
            CppTranslationContext context,
            String builderClass,
            String builder,
            TypeDescriptor type,
            String value
    ) {
        String appendDescriptor = appendDescriptor(type);
        String argument = value;
        if ("(I)Ljava/lang/StringBuilder;".equals(appendDescriptor)
                && ("jbyte".equals(context.jniType(type)) || "jshort".equals(context.jniType(type)))) {
            argument = "static_cast<jint>(" + value + ")";
        }
        appendWithDescriptor(context, builderClass, builder, appendDescriptor, argument);
    }

    private void appendWithDescriptor(
            CppTranslationContext context,
            String builderClass,
            String builder,
            String descriptor,
            String argument
    ) {
        String method = context.nextTemp("appendMethod");
        context.nestedLine("jmethodID " + method + " = " + context.methodCache()
                + ".get(env, " + builderClass + ", \"java/lang/StringBuilder\", \"append\", "
                + CppStringEscaper.quote(descriptor) + ", false);");
        context.nestedLine("env->CallObjectMethod(" + builder + ", " + method + ", " + argument + ");");
    }

    private String appendDescriptor(TypeDescriptor type) {
        return switch (type.valueType()) {
            case BOOLEAN -> "(Z)Ljava/lang/StringBuilder;";
            case BYTE, SHORT, INT -> "(I)Ljava/lang/StringBuilder;";
            case CHAR -> "(C)Ljava/lang/StringBuilder;";
            case LONG -> "(J)Ljava/lang/StringBuilder;";
            case FLOAT -> "(F)Ljava/lang/StringBuilder;";
            case DOUBLE -> "(D)Ljava/lang/StringBuilder;";
            case ARRAY -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
            case OBJECT -> "java/lang/String".equals(type.internalName())
                    ? "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
                    : "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
            case VOID -> throw new IllegalArgumentException("Cannot append void");
        };
    }

    private void emitUnsupported(CppTranslationContext context, DynamicInvocationReference reference, String reason) {
        context.line("frostjni::ExceptionUtils::throwUnsupportedOperationException(env, "
                + CppStringEscaper.quote("unsupported invokedynamic " + reason + ": "
                + reference.name() + reference.descriptor()) + ");");
        context.line(context.fallbackReturn());
    }
}


