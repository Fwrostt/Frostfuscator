package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppStringEscaper;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates JVM type/object creation instructions.
 */
public final class TypeInstructionTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(
            IROpcode.CHECKCAST,
            IROpcode.INSTANCEOF,
            IROpcode.NEW,
            IROpcode.ANEWARRAY,
            IROpcode.NEWARRAY,
            IROpcode.MULTIANEWARRAY
    );

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        switch (instruction.opcode()) {
            case CHECKCAST -> checkcast(context, (String) instruction.operands().get(0));
            case INSTANCEOF -> instanceOf(context, (String) instruction.operands().get(0));
            case NEW -> newObject(context, (String) instruction.operands().get(0));
            case ANEWARRAY -> newObjectArray(context, (String) instruction.operands().get(0));
            case NEWARRAY -> newPrimitiveArray(context, (Integer) instruction.operands().get(0));
            case MULTIANEWARRAY -> multiNewArray(context, (String) instruction.operands().get(0), (Integer) instruction.operands().get(1));
            default -> throw new IllegalArgumentException("Unsupported type instruction " + instruction.opcode());
        }
    }

    private void checkcast(CppTranslationContext context, String type) {
        context.line("{");
        context.nestedLine("jobject value = frame.stack[frame.sp - 1].l;");
        context.nestedLine("if (value != nullptr) {");
        context.raw("            jclass clazz = " + context.classCache() + ".get(env, " + CppStringEscaper.quote(type) + ");\n");
        context.raw("            if (!env->IsInstanceOf(value, clazz)) {\n");
        context.raw("                frostjni::ExceptionUtils::throwClassCastException(env, " + CppStringEscaper.quote(type) + ");\n");
        context.raw("                " + context.fallbackReturn() + "\n");
        context.raw("            }\n");
        context.nestedLine("}");
        context.line("}");
    }

    private void instanceOf(CppTranslationContext context, String type) {
        context.line("{");
        context.nestedLine("jobject value = frame.stack[--frame.sp].l;");
        context.nestedLine("jclass clazz = " + context.classCache() + ".get(env, " + CppStringEscaper.quote(type) + ");");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result.i = (value != nullptr && env->IsInstanceOf(value, clazz)) ? 1 : 0;");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private void newObject(CppTranslationContext context, String type) {
        context.line("{");
        context.nestedLine("jclass clazz = " + context.classCache() + ".get(env, " + CppStringEscaper.quote(type) + ");");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result.l = env->AllocObject(clazz);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private void newObjectArray(CppTranslationContext context, String type) {
        context.line("{");
        context.nestedLine("jint length = frame.stack[--frame.sp].i;");
        context.nestedLine("jclass elementClass = " + context.classCache() + ".get(env, " + CppStringEscaper.quote(type) + ");");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result.l = env->NewObjectArray(length, elementClass, nullptr);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private void newPrimitiveArray(CppTranslationContext context, int arrayType) {
        String factory = switch (arrayType) {
            case 4 -> "NewBooleanArray";
            case 5 -> "NewCharArray";
            case 6 -> "NewFloatArray";
            case 7 -> "NewDoubleArray";
            case 8 -> "NewByteArray";
            case 9 -> "NewShortArray";
            case 10 -> "NewIntArray";
            case 11 -> "NewLongArray";
            default -> throw new IllegalArgumentException("Unknown NEWARRAY type " + arrayType);
        };
        context.line("{");
        context.nestedLine("jint length = frame.stack[--frame.sp].i;");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result.l = env->" + factory + "(length);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private void multiNewArray(CppTranslationContext context, String descriptor, int dimensions) {
        context.line("{");
        context.nestedLine("jint dimensions[" + dimensions + "]{};");
        for (int index = dimensions - 1; index >= 0; index--) {
            context.nestedLine("dimensions[" + index + "] = frame.stack[--frame.sp].i;");
        }
        context.nestedLine("jvalue result{};");
        context.nestedLine("result.l = frostjni::ArrayUtils::newMultiArray(env, " + CppStringEscaper.quote(descriptor) + ", dimensions, " + dimensions + ");");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }
}



