package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.core.model.LabelModel;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Preserves JVM branch control flow with C++ labels and gotos.
 */
public final class BranchTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(
            IROpcode.LABEL,
            IROpcode.IFEQ,
            IROpcode.IFNE,
            IROpcode.IFLT,
            IROpcode.IFGE,
            IROpcode.IFGT,
            IROpcode.IFLE,
            IROpcode.IF_ICMPEQ,
            IROpcode.IF_ICMPNE,
            IROpcode.IF_ICMPLT,
            IROpcode.IF_ICMPGE,
            IROpcode.IF_ICMPGT,
            IROpcode.IF_ICMPLE,
            IROpcode.IF_ACMPEQ,
            IROpcode.IF_ACMPNE,
            IROpcode.IFNULL,
            IROpcode.IFNONNULL,
            IROpcode.GOTO
    );

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        LabelModel label = (LabelModel) instruction.operands().get(0);
        String labelName = context.labelGenerator().labelName(label);
        if (instruction.opcode() == IROpcode.LABEL) {
            context.label(labelName);
        } else if (instruction.opcode() == IROpcode.GOTO) {
            context.line("goto " + labelName + ";");
        } else if (isObjectUnary(instruction.opcode())) {
            translateObjectUnary(context, instruction.opcode(), labelName);
        } else if (isObjectBinary(instruction.opcode())) {
            translateObjectBinary(context, instruction.opcode(), labelName);
        } else if (isUnary(instruction.opcode())) {
            translateUnary(context, instruction.opcode(), labelName);
        } else {
            translateBinary(context, instruction.opcode(), labelName);
        }
    }

    private void translateObjectUnary(CppTranslationContext context, IROpcode opcode, String labelName) {
        String operator = opcode == IROpcode.IFNULL ? "==" : "!=";
        context.line("{");
        context.nestedLine("jobject value = frame.stack[--frame.sp].l;");
        context.nestedLine("if (value " + operator + " nullptr) goto " + labelName + ";");
        context.line("}");
    }

    private void translateObjectBinary(CppTranslationContext context, IROpcode opcode, String labelName) {
        String operator = opcode == IROpcode.IF_ACMPEQ ? "==" : "!=";
        context.line("{");
        context.nestedLine("jobject right = frame.stack[--frame.sp].l;");
        context.nestedLine("jobject left = frame.stack[--frame.sp].l;");
        context.nestedLine("if (left " + operator + " right) goto " + labelName + ";");
        context.line("}");
    }

    private void translateUnary(CppTranslationContext context, IROpcode opcode, String labelName) {
        String operator = switch (opcode) {
            case IFEQ -> "==";
            case IFNE -> "!=";
            case IFLT -> "<";
            case IFGE -> ">=";
            case IFGT -> ">";
            case IFLE -> "<=";
            default -> throw new IllegalArgumentException("Not a unary branch " + opcode);
        };
        context.line("{");
        context.nestedLine("jint value = frame.stack[--frame.sp].i;");
        context.nestedLine("if (value " + operator + " 0) goto " + labelName + ";");
        context.line("}");
    }

    private void translateBinary(CppTranslationContext context, IROpcode opcode, String labelName) {
        String operator = switch (opcode) {
            case IF_ICMPEQ -> "==";
            case IF_ICMPNE -> "!=";
            case IF_ICMPLT -> "<";
            case IF_ICMPGE -> ">=";
            case IF_ICMPGT -> ">";
            case IF_ICMPLE -> "<=";
            default -> throw new IllegalArgumentException("Not a binary branch " + opcode);
        };
        context.line("{");
        context.nestedLine("jint right = frame.stack[--frame.sp].i;");
        context.nestedLine("jint left = frame.stack[--frame.sp].i;");
        context.nestedLine("if (left " + operator + " right) goto " + labelName + ";");
        context.line("}");
    }

    private boolean isUnary(IROpcode opcode) {
        return switch (opcode) {
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> true;
            default -> false;
        };
    }

    private boolean isObjectUnary(IROpcode opcode) {
        return opcode == IROpcode.IFNULL || opcode == IROpcode.IFNONNULL;
    }

    private boolean isObjectBinary(IROpcode opcode) {
        return opcode == IROpcode.IF_ACMPEQ || opcode == IROpcode.IF_ACMPNE;
    }
}


