package dev.frost.obfuscator.jni.generator.cpp;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.generator.cpp.translator.ArithmeticTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.ArrayInstructionTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.BranchTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.ConstantTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.ComparisonTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.ConversionTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.DynamicInvocationTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.ExceptionTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.FieldTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.InstructionTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.MethodInvokeTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.ReturnTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.StackTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.SwitchTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.SynchronizationTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.TypeInstructionTranslator;
import dev.frost.obfuscator.jni.generator.cpp.translator.VariableTranslator;

import java.util.List;

/**
 * Dispatches IR instructions to focused translator implementations.
 */
public final class CppInstructionGenerator {
    private final List<InstructionTranslator> translators = List.of(
            new ConstantTranslator(),
            new StackTranslator(),
            new VariableTranslator(),
            new ArithmeticTranslator(),
            new ConversionTranslator(),
            new ComparisonTranslator(),
            new ReturnTranslator(),
            new BranchTranslator(),
            new SwitchTranslator(),
            new TypeInstructionTranslator(),
            new ArrayInstructionTranslator(),
            new ExceptionTranslator(),
            new SynchronizationTranslator(),
            new MethodInvokeTranslator(),
            new FieldTranslator(),
            new DynamicInvocationTranslator()
    );

    public void appendInstructions(CppTranslationContext context, List<IRInstruction> instructions) {
        for (IRInstruction instruction : instructions) {
            translate(context, instruction);
        }
    }

    private void translate(CppTranslationContext context, IRInstruction instruction) {
        for (InstructionTranslator translator : translators) {
            if (translator.supports(instruction.opcode())) {
                translator.translate(context, instruction);
                return;
            }
        }
        context.line("// Unsupported instruction preserved for future backend work: " + instruction.opcode());
    }
}


