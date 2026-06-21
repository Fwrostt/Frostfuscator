package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates JVM return instructions.
 */
public final class ReturnTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(IROpcode.IRETURN, IROpcode.LRETURN, IROpcode.FRETURN, IROpcode.DRETURN, IROpcode.ARETURN, IROpcode.RETURN);

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        switch (instruction.opcode()) {
            case IRETURN -> context.line("return frame.stack[--frame.sp].i;");
            case LRETURN -> context.line("return frame.stack[--frame.sp].j;");
            case FRETURN -> context.line("return frame.stack[--frame.sp].f;");
            case DRETURN -> context.line("return frame.stack[--frame.sp].d;");
            case ARETURN -> context.line("return " + context.stackValue(context.returnType(), "frame.stack[--frame.sp]") + ";");
            case RETURN -> context.line("return;");
            default -> throw new IllegalArgumentException("Unsupported return opcode " + instruction.opcode());
        }
    }
}


