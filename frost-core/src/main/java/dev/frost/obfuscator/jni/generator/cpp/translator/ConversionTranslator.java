package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates JVM primitive conversion instructions.
 */
public final class ConversionTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(
            IROpcode.I2L,
            IROpcode.I2F,
            IROpcode.I2D,
            IROpcode.L2I,
            IROpcode.L2F,
            IROpcode.L2D,
            IROpcode.F2I,
            IROpcode.F2L,
            IROpcode.F2D,
            IROpcode.D2I,
            IROpcode.D2L,
            IROpcode.D2F,
            IROpcode.I2B,
            IROpcode.I2C,
            IROpcode.I2S
    );

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        Conversion conversion = conversion(instruction.opcode());
        context.line("{");
        context.nestedLine(conversion.sourceType() + " value = frame.stack[--frame.sp]." + conversion.sourceMember() + ";");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result." + conversion.targetMember() + " = static_cast<" + conversion.targetType() + ">(value);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private Conversion conversion(IROpcode opcode) {
        return switch (opcode) {
            case I2L -> new Conversion("jint", "i", "jlong", "j");
            case I2F -> new Conversion("jint", "i", "jfloat", "f");
            case I2D -> new Conversion("jint", "i", "jdouble", "d");
            case L2I -> new Conversion("jlong", "j", "jint", "i");
            case L2F -> new Conversion("jlong", "j", "jfloat", "f");
            case L2D -> new Conversion("jlong", "j", "jdouble", "d");
            case F2I -> new Conversion("jfloat", "f", "jint", "i");
            case F2L -> new Conversion("jfloat", "f", "jlong", "j");
            case F2D -> new Conversion("jfloat", "f", "jdouble", "d");
            case D2I -> new Conversion("jdouble", "d", "jint", "i");
            case D2L -> new Conversion("jdouble", "d", "jlong", "j");
            case D2F -> new Conversion("jdouble", "d", "jfloat", "f");
            case I2B -> new Conversion("jint", "i", "jbyte", "i");
            case I2C -> new Conversion("jint", "i", "jchar", "i");
            case I2S -> new Conversion("jint", "i", "jshort", "i");
            default -> throw new IllegalStateException("Unsupported conversion opcode " + opcode);
        };
    }

    private record Conversion(String sourceType, String sourceMember, String targetType, String targetMember) {
    }
}
