package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates JVM comparison instructions that collapse wide/floating values to int.
 */
public final class ComparisonTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(
            IROpcode.LCMP,
            IROpcode.FCMPL,
            IROpcode.FCMPG,
            IROpcode.DCMPL,
            IROpcode.DCMPG
    );

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        if (instruction.opcode() == IROpcode.LCMP) {
            translateLong(context);
            return;
        }
        translateFloating(context, instruction.opcode());
    }

    private void translateLong(CppTranslationContext context) {
        context.line("{");
        context.nestedLine("jlong b = frame.stack[--frame.sp].j;");
        context.nestedLine("jlong a = frame.stack[--frame.sp].j;");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result.i = (a > b) ? 1 : ((a == b) ? 0 : -1);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private void translateFloating(CppTranslationContext context, IROpcode opcode) {
        boolean wide = opcode == IROpcode.DCMPL || opcode == IROpcode.DCMPG;
        boolean nanHigh = opcode == IROpcode.FCMPG || opcode == IROpcode.DCMPG;
        String type = wide ? "jdouble" : "jfloat";
        String member = wide ? "d" : "f";
        context.line("{");
        context.nestedLine(type + " b = frame.stack[--frame.sp]." + member + ";");
        context.nestedLine(type + " a = frame.stack[--frame.sp]." + member + ";");
        context.nestedLine("jvalue result{};");
        context.nestedLine("if (std::isnan(a) || std::isnan(b)) {");
        context.raw("            result.i = " + (nanHigh ? "1" : "-1") + ";\n");
        context.raw("        } else {\n");
        context.raw("            result.i = (a > b) ? 1 : ((a == b) ? 0 : -1);\n");
        context.raw("        }\n");
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }
}
