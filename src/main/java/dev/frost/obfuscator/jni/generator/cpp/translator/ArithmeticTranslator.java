package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.Map;
import java.util.Set;

/**
 * Translates integer arithmetic instructions.
 */
public final class ArithmeticTranslator implements InstructionTranslator {
    private static final Map<IROpcode, String> BINARY_OPERATORS = Map.ofEntries(
            Map.entry(IROpcode.IADD, "+"),
            Map.entry(IROpcode.ISUB, "-"),
            Map.entry(IROpcode.IMUL, "*"),
            Map.entry(IROpcode.IDIV, "/"),
            Map.entry(IROpcode.IREM, "%"),
            Map.entry(IROpcode.LADD, "+"),
            Map.entry(IROpcode.LSUB, "-"),
            Map.entry(IROpcode.LMUL, "*"),
            Map.entry(IROpcode.LDIV, "/"),
            Map.entry(IROpcode.LREM, "%"),
            Map.entry(IROpcode.FADD, "+"),
            Map.entry(IROpcode.FSUB, "-"),
            Map.entry(IROpcode.FMUL, "*"),
            Map.entry(IROpcode.FDIV, "/"),
            Map.entry(IROpcode.FREM, "%"),
            Map.entry(IROpcode.DADD, "+"),
            Map.entry(IROpcode.DSUB, "-"),
            Map.entry(IROpcode.DMUL, "*"),
            Map.entry(IROpcode.DDIV, "/"),
            Map.entry(IROpcode.DREM, "%"),
            Map.entry(IROpcode.ISHL, "<<"),
            Map.entry(IROpcode.ISHR, ">>"),
            Map.entry(IROpcode.IAND, "&"),
            Map.entry(IROpcode.IOR, "|"),
            Map.entry(IROpcode.IXOR, "^"),
            Map.entry(IROpcode.LSHL, "<<"),
            Map.entry(IROpcode.LSHR, ">>"),
            Map.entry(IROpcode.LAND, "&"),
            Map.entry(IROpcode.LOR, "|"),
            Map.entry(IROpcode.LXOR, "^")
    );
    private static final Set<IROpcode> OPCODES = Set.of(
            IROpcode.IADD,
            IROpcode.ISUB,
            IROpcode.IMUL,
            IROpcode.IDIV,
            IROpcode.IREM,
            IROpcode.INEG,
            IROpcode.LADD,
            IROpcode.LSUB,
            IROpcode.LMUL,
            IROpcode.LDIV,
            IROpcode.LREM,
            IROpcode.LNEG,
            IROpcode.FADD,
            IROpcode.FSUB,
            IROpcode.FMUL,
            IROpcode.FDIV,
            IROpcode.FREM,
            IROpcode.FNEG,
            IROpcode.DADD,
            IROpcode.DSUB,
            IROpcode.DMUL,
            IROpcode.DDIV,
            IROpcode.DREM,
            IROpcode.DNEG,
            IROpcode.ISHL,
            IROpcode.ISHR,
            IROpcode.IUSHR,
            IROpcode.IAND,
            IROpcode.IOR,
            IROpcode.IXOR,
            IROpcode.LSHL,
            IROpcode.LSHR,
            IROpcode.LUSHR,
            IROpcode.LAND,
            IROpcode.LOR,
            IROpcode.LXOR
    );

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        if (isNegation(instruction.opcode())) {
            String type = typeName(instruction.opcode());
            String member = memberName(instruction.opcode());
            context.line("{");
            context.nestedLine(type + " value = frame.stack[--frame.sp]." + member + ";");
            context.nestedLine("jvalue result{};");
            context.nestedLine("result." + member + " = -value;");
            context.nestedLine("frame.stack[frame.sp++] = result;");
            context.line("}");
            return;
        }
        if (instruction.opcode() == IROpcode.IUSHR || instruction.opcode() == IROpcode.LUSHR) {
            translateUnsignedShift(context, instruction.opcode());
            return;
        }
        if (instruction.opcode() == IROpcode.ISHL
                || instruction.opcode() == IROpcode.ISHR
                || instruction.opcode() == IROpcode.LSHL
                || instruction.opcode() == IROpcode.LSHR) {
            translateShift(context, instruction.opcode(), BINARY_OPERATORS.get(instruction.opcode()));
            return;
        }
        translateBinary(context, instruction.opcode(), BINARY_OPERATORS.get(instruction.opcode()));
    }

    private void translateShift(CppTranslationContext context, IROpcode opcode, String operator) {
        String type = opcode.name().startsWith("L") ? "jlong" : "jint";
        String member = opcode.name().startsWith("L") ? "j" : "i";
        context.line("{");
        context.nestedLine("jint shift = frame.stack[--frame.sp].i;");
        context.nestedLine(type + " value = frame.stack[--frame.sp]." + member + ";");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result." + member + " = value " + operator + " shift;");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private void translateUnsignedShift(CppTranslationContext context, IROpcode opcode) {
        boolean wide = opcode == IROpcode.LUSHR;
        String type = wide ? "jlong" : "jint";
        String unsignedType = wide ? "uint64_t" : "uint32_t";
        String member = wide ? "j" : "i";
        context.line("{");
        context.nestedLine("jint shift = frame.stack[--frame.sp].i;");
        context.nestedLine(type + " value = frame.stack[--frame.sp]." + member + ";");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result." + member + " = static_cast<" + type + ">(static_cast<" + unsignedType + ">(value) >> shift);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private void translateBinary(CppTranslationContext context, IROpcode opcode, String operator) {
        String type = typeName(opcode);
        String member = memberName(opcode);
        context.line("{");
        context.nestedLine(type + " b = frame.stack[--frame.sp]." + member + ";");
        context.nestedLine(type + " a = frame.stack[--frame.sp]." + member + ";");
        if (opcode == IROpcode.IDIV || opcode == IROpcode.IREM || opcode == IROpcode.LDIV || opcode == IROpcode.LREM) {
            context.nestedLine("if (b == 0) {");
            context.raw("            frostjni::ExceptionUtils::throwArithmeticException(env, \"/ by zero\");\n");
            context.raw("            " + context.fallbackReturn() + "\n");
            context.raw("        }\n");
        }
        context.nestedLine("jvalue result{};");
        if (opcode == IROpcode.FREM) {
            context.nestedLine("result.f = std::fmod(a, b);");
        } else if (opcode == IROpcode.DREM) {
            context.nestedLine("result.d = std::fmod(a, b);");
        } else {
            context.nestedLine("result." + member + " = a " + operator + " b;");
        }
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private boolean isNegation(IROpcode opcode) {
        return opcode == IROpcode.INEG || opcode == IROpcode.LNEG || opcode == IROpcode.FNEG || opcode == IROpcode.DNEG;
    }

    private String typeName(IROpcode opcode) {
        return switch (opcode.name().charAt(0)) {
            case 'L' -> "jlong";
            case 'F' -> "jfloat";
            case 'D' -> "jdouble";
            default -> "jint";
        };
    }

    private String memberName(IROpcode opcode) {
        return switch (opcode.name().charAt(0)) {
            case 'L' -> "j";
            case 'F' -> "f";
            case 'D' -> "d";
            default -> "i";
        };
    }
}


