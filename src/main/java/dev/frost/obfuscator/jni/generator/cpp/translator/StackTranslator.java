package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates simple operand stack manipulation instructions.
 */
public final class StackTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(
            IROpcode.POP,
            IROpcode.POP2,
            IROpcode.DUP,
            IROpcode.DUP_X1,
            IROpcode.DUP_X2,
            IROpcode.DUP2,
            IROpcode.DUP2_X1,
            IROpcode.DUP2_X2,
            IROpcode.SWAP
    );

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        switch (instruction.opcode()) {
            case POP -> context.line("--frame.sp;");
            case POP2 -> context.line("frame.sp -= 2;");
            case DUP -> {
                context.line("frame.stack[frame.sp] = frame.stack[frame.sp - 1];");
                context.line("frame.sp++;");
            }
            case DUP_X1 -> {
                context.line("{");
                context.nestedLine("jvalue value1 = frame.stack[frame.sp - 1];");
                context.nestedLine("jvalue value2 = frame.stack[frame.sp - 2];");
                context.nestedLine("frame.stack[frame.sp - 1] = value2;");
                context.nestedLine("frame.stack[frame.sp - 2] = value1;");
                context.nestedLine("frame.stack[frame.sp++] = value1;");
                context.line("}");
            }
            case DUP_X2 -> {
                context.line("{");
                context.nestedLine("jvalue value1 = frame.stack[frame.sp - 1];");
                context.nestedLine("jvalue value2 = frame.stack[frame.sp - 2];");
                context.nestedLine("jvalue value3 = frame.stack[frame.sp - 3];");
                context.nestedLine("frame.stack[frame.sp - 1] = value2;");
                context.nestedLine("frame.stack[frame.sp - 2] = value3;");
                context.nestedLine("frame.stack[frame.sp - 3] = value1;");
                context.nestedLine("frame.stack[frame.sp++] = value1;");
                context.line("}");
            }
            case DUP2 -> {
                context.line("frame.stack[frame.sp] = frame.stack[frame.sp - 2];");
                context.line("frame.stack[frame.sp + 1] = frame.stack[frame.sp - 1];");
                context.line("frame.sp += 2;");
            }
            case DUP2_X1 -> {
                context.line("{");
                context.nestedLine("jvalue value1 = frame.stack[frame.sp - 1];");
                context.nestedLine("jvalue value2 = frame.stack[frame.sp - 2];");
                context.nestedLine("jvalue value3 = frame.stack[frame.sp - 3];");
                context.nestedLine("frame.stack[frame.sp - 1] = value3;");
                context.nestedLine("frame.stack[frame.sp - 2] = value1;");
                context.nestedLine("frame.stack[frame.sp - 3] = value2;");
                context.nestedLine("frame.stack[frame.sp++] = value2;");
                context.nestedLine("frame.stack[frame.sp++] = value1;");
                context.line("}");
            }
            case DUP2_X2 -> {
                context.line("{");
                context.nestedLine("jvalue value1 = frame.stack[frame.sp - 1];");
                context.nestedLine("jvalue value2 = frame.stack[frame.sp - 2];");
                context.nestedLine("jvalue value3 = frame.stack[frame.sp - 3];");
                context.nestedLine("jvalue value4 = frame.stack[frame.sp - 4];");
                context.nestedLine("frame.stack[frame.sp - 1] = value3;");
                context.nestedLine("frame.stack[frame.sp - 2] = value4;");
                context.nestedLine("frame.stack[frame.sp - 3] = value1;");
                context.nestedLine("frame.stack[frame.sp - 4] = value2;");
                context.nestedLine("frame.stack[frame.sp++] = value2;");
                context.nestedLine("frame.stack[frame.sp++] = value1;");
                context.line("}");
            }
            case SWAP -> {
                context.line("{");
                context.nestedLine("jvalue value1 = frame.stack[frame.sp - 1];");
                context.nestedLine("frame.stack[frame.sp - 1] = frame.stack[frame.sp - 2];");
                context.nestedLine("frame.stack[frame.sp - 2] = value1;");
                context.line("}");
            }
            default -> throw new IllegalStateException("Unsupported stack opcode " + instruction.opcode());
        }
    }
}


