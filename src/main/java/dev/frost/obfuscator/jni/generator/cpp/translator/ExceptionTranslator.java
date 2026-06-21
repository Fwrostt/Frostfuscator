package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

/**
 * Translates explicit JVM exception throwing.
 */
public final class ExceptionTranslator implements InstructionTranslator {
    @Override
    public boolean supports(IROpcode opcode) {
        return opcode == IROpcode.ATHROW;
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        context.line("{");
        context.nestedLine("jthrowable throwable = static_cast<jthrowable>(frame.stack[--frame.sp].l);");
        context.nestedLine("env->Throw(throwable);");
        context.nestedLine(context.fallbackReturn());
        context.line("}");
    }
}


