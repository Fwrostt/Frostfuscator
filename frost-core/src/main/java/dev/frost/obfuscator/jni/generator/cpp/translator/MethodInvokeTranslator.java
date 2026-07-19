package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.descriptor.MethodDescriptor;
import dev.frost.obfuscator.jni.core.descriptor.TypeDescriptor;
import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.core.model.MethodReference;
import dev.frost.obfuscator.jni.generator.cpp.CppStringEscaper;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Translates method invocation instructions through JNI and runtime caches.
 */
public final class MethodInvokeTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(IROpcode.INVOKESTATIC, IROpcode.INVOKEVIRTUAL, IROpcode.INVOKESPECIAL, IROpcode.INVOKEINTERFACE);

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        MethodReference reference = (MethodReference) instruction.operands().get(0);
        MethodDescriptor descriptor = context.descriptorParser().parseMethod(reference.descriptor());
        context.line("{");
        List<String> arguments = popArguments(context, descriptor.parameters());
        context.nestedLine("jclass clazz = " + context.classCache() + ".get(env, " + CppStringEscaper.quote(reference.ownerInternalName()) + ");");
        if (instruction.opcode() == IROpcode.INVOKESTATIC) {
            translateStatic(context, reference, descriptor, arguments);
        } else {
            translateInstance(context, instruction.opcode(), reference, descriptor, arguments);
        }
        context.line("}");
    }

    private void translateStatic(
            CppTranslationContext context,
            MethodReference reference,
            MethodDescriptor descriptor,
            List<String> arguments
    ) {
        context.nestedLine("jmethodID method = " + context.methodCache() + ".get(env, clazz, "
                + CppStringEscaper.quote(reference.ownerInternalName()) + ", "
                + CppStringEscaper.quote(reference.name()) + ", "
                + CppStringEscaper.quote(reference.descriptor()) + ", true);");
        emitCall(context, descriptor.returnType(), "env->" + callName("Static", descriptor.returnType())
                + "(clazz, method" + argumentSuffix(arguments) + ")");
    }

    private void translateInstance(
            CppTranslationContext context,
            IROpcode opcode,
            MethodReference reference,
            MethodDescriptor descriptor,
            List<String> arguments
    ) {
        context.nestedLine("jobject receiver = frame.stack[--frame.sp].l;");
        context.nestedLine("jmethodID method = " + context.methodCache() + ".get(env, clazz, "
                + CppStringEscaper.quote(reference.ownerInternalName()) + ", "
                + CppStringEscaper.quote(reference.name()) + ", "
                + CppStringEscaper.quote(reference.descriptor()) + ", false);");
        if (opcode == IROpcode.INVOKESPECIAL) {
            emitCall(context, descriptor.returnType(), "env->" + callName("Nonvirtual", descriptor.returnType())
                    + "(receiver, clazz, method" + argumentSuffix(arguments) + ")");
        } else {
            emitCall(context, descriptor.returnType(), "env->" + callName("", descriptor.returnType())
                    + "(receiver, method" + argumentSuffix(arguments) + ")");
        }
    }

    private List<String> popArguments(CppTranslationContext context, List<TypeDescriptor> parameters) {
        List<String> arguments = new ArrayList<>();
        for (int i = parameters.size() - 1; i >= 0; i--) {
            TypeDescriptor parameter = parameters.get(i);
            String name = "argValue" + i;
            context.nestedLine(context.jniType(parameter) + " " + name + " = "
                    + context.stackValue(parameter, "frame.stack[--frame.sp]") + ";");
            arguments.add(0, name);
        }
        return arguments;
    }

    private void emitCall(CppTranslationContext context, TypeDescriptor returnType, String callExpression) {
        if (returnType.isVoid()) {
            context.nestedLine(callExpression + ";");
            return;
        }
        context.nestedLine("jvalue result{};");
        context.nestedLine("result." + context.jvalueMember(returnType) + " = " + callExpression + ";");
        context.nestedLine("frame.stack[frame.sp++] = result;");
    }

    private String argumentSuffix(List<String> arguments) {
        return arguments.isEmpty() ? "" : ", " + String.join(", ", arguments);
    }

    private String callName(String mode, TypeDescriptor returnType) {
        String returnKind = switch (returnType.valueType()) {
            case VOID -> "Void";
            case BOOLEAN -> "Boolean";
            case BYTE -> "Byte";
            case CHAR -> "Char";
            case SHORT -> "Short";
            case INT -> "Int";
            case LONG -> "Long";
            case FLOAT -> "Float";
            case DOUBLE -> "Double";
            case ARRAY, OBJECT -> "Object";
        };
        return "Call" + mode + returnKind + "Method";
    }
}



