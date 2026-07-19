package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

/**
 * Focused translator for a family of IR opcodes.
 */
public interface InstructionTranslator {
    boolean supports(IROpcode opcode);

    void translate(CppTranslationContext context, IRInstruction instruction);
}


