package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates monitor enter/exit instructions to JNI monitor APIs.
 */
public final class SynchronizationTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(IROpcode.MONITORENTER, IROpcode.MONITOREXIT);

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        String api = instruction.opcode() == IROpcode.MONITORENTER ? "MonitorEnter" : "MonitorExit";
        context.line("{");
        context.nestedLine("jobject monitor = frame.stack[--frame.sp].l;");
        context.nestedLine("if (env->" + api + "(monitor) != JNI_OK) {");
        context.raw("            " + context.fallbackReturn() + "\n");
        context.nestedLine("}");
        context.line("}");
    }
}


