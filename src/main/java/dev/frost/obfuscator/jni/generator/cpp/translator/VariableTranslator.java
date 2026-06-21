package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates local variable load and store instructions.
 */
public final class VariableTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(
            IROpcode.ILOAD, IROpcode.ISTORE, IROpcode.ALOAD, IROpcode.ASTORE,
            IROpcode.LLOAD, IROpcode.LSTORE, IROpcode.FLOAD, IROpcode.FSTORE,
            IROpcode.DLOAD, IROpcode.DSTORE, IROpcode.IINC
    );

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        int local = (Integer) instruction.operands().get(0);
        if (instruction.opcode() == IROpcode.IINC) {
            int increment = (Integer) instruction.operands().get(1);
            context.line("frame.locals[" + local + "].i += " + increment + ";");
        } else if (isLoad(instruction.opcode())) {
            context.line("frame.stack[frame.sp++] = frame.locals[" + local + "];");
        } else {
            context.line("frame.locals[" + local + "] = frame.stack[--frame.sp];");
        }
    }

    private boolean isLoad(IROpcode opcode) {
        return opcode == IROpcode.ILOAD
                || opcode == IROpcode.ALOAD
                || opcode == IROpcode.LLOAD
                || opcode == IROpcode.FLOAD
                || opcode == IROpcode.DLOAD;
    }
}


