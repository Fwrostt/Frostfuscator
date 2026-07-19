package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.core.model.SwitchModel;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates table and lookup switches to C++ switch/goto control flow.
 */
public final class SwitchTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(IROpcode.TABLESWITCH, IROpcode.LOOKUPSWITCH);

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        SwitchModel switchModel = (SwitchModel) instruction.operands().get(0);
        context.line("{");
        context.nestedLine("jint key = frame.stack[--frame.sp].i;");
        context.nestedLine("switch (key) {");
        for (int index = 0; index < switchModel.keys().size(); index++) {
            context.raw("            case " + switchModel.keys().get(index) + ": goto "
                    + context.labelGenerator().labelName(switchModel.labels().get(index)) + ";\n");
        }
        context.raw("            default: goto " + context.labelGenerator().labelName(switchModel.defaultLabel()) + ";\n");
        context.nestedLine("}");
        context.line("}");
    }
}


