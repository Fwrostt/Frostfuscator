package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppStringEscaper;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;
import org.objectweb.asm.Type;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates integer and string constants.
 */
public final class ConstantTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(IROpcode.ACONST_NULL, IROpcode.ICONST, IROpcode.BIPUSH, IROpcode.SIPUSH, IROpcode.LDC);

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        Object value = instruction.operands().get(0);
        if (instruction.opcode() == IROpcode.ACONST_NULL) {
            context.line("frame.stack[frame.sp++].l = nullptr;");
            return;
        }
        if (instruction.opcode() != IROpcode.LDC || value instanceof Integer) {
            context.line("frame.stack[frame.sp++].i = " + value + ";");
            return;
        }
        if (value instanceof Long longValue) {
            context.line("frame.stack[frame.sp++].j = " + longValue + "LL;");
            return;
        }
        if (value instanceof Float floatValue) {
            context.line("frame.stack[frame.sp++].f = " + floatValue + "f;");
            return;
        }
        if (value instanceof Double doubleValue) {
            context.line("frame.stack[frame.sp++].d = " + doubleValue + ";");
            return;
        }
        if (value instanceof String stringValue) {
            context.line("frame.stack[frame.sp++].l = env->NewStringUTF(" + CppStringEscaper.quote(stringValue) + ");");
            return;
        }
        if (value instanceof Type typeValue) {
            context.line("frame.stack[frame.sp++].l = " + context.classCache() + ".get(env, " + CppStringEscaper.quote(classLiteralName(typeValue)) + ");");
            return;
        }
        context.line("// Unsupported LDC constant: " + value);
    }

    private String classLiteralName(Type type) {
        if (type.getSort() == Type.OBJECT) {
            return type.getInternalName();
        }
        if (type.getSort() == Type.ARRAY) {
            return type.getDescriptor();
        }
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> "java/lang/Object";
        };
    }
}



