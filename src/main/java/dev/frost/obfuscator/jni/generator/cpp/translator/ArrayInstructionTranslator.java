package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates JVM array load, store, and length instructions.
 */
public final class ArrayInstructionTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(
            IROpcode.IALOAD, IROpcode.LALOAD, IROpcode.FALOAD, IROpcode.DALOAD,
            IROpcode.AALOAD, IROpcode.BALOAD, IROpcode.CALOAD, IROpcode.SALOAD,
            IROpcode.IASTORE, IROpcode.LASTORE, IROpcode.FASTORE, IROpcode.DASTORE,
            IROpcode.AASTORE, IROpcode.BASTORE, IROpcode.CASTORE, IROpcode.SASTORE,
            IROpcode.ARRAYLENGTH
    );

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        if (instruction.opcode() == IROpcode.ARRAYLENGTH) {
            context.line("{");
            context.nestedLine("jarray array = static_cast<jarray>(frame.stack[--frame.sp].l);");
            context.nestedLine("jvalue result{};");
            context.nestedLine("result.i = env->GetArrayLength(array);");
            context.nestedLine("frame.stack[frame.sp++] = result;");
            context.line("}");
        } else if (isLoad(instruction.opcode())) {
            load(context, spec(instruction.opcode()));
        } else {
            store(context, spec(instruction.opcode()));
        }
    }

    private void load(CppTranslationContext context, ArraySpec spec) {
        context.line("{");
        context.nestedLine("jint index = frame.stack[--frame.sp].i;");
        context.nestedLine(spec.arrayType() + " array = static_cast<" + spec.arrayType() + ">(frame.stack[--frame.sp].l);");
        context.nestedLine("jvalue result{};");
        if (spec.objectArray()) {
            context.nestedLine("result.l = env->GetObjectArrayElement(array, index);");
        } else {
            context.nestedLine(spec.elementType() + " value{};");
            context.nestedLine("env->Get" + spec.jniName() + "ArrayRegion(array, index, 1, &value);");
            context.nestedLine("result." + spec.member() + " = value;");
        }
        context.nestedLine("frame.stack[frame.sp++] = result;");
        context.line("}");
    }

    private void store(CppTranslationContext context, ArraySpec spec) {
        context.line("{");
        if (spec.objectArray()) {
            context.nestedLine("jobject value = frame.stack[--frame.sp].l;");
        } else {
            context.nestedLine(spec.elementType() + " value = frame.stack[--frame.sp]." + spec.member() + ";");
        }
        context.nestedLine("jint index = frame.stack[--frame.sp].i;");
        context.nestedLine(spec.arrayType() + " array = static_cast<" + spec.arrayType() + ">(frame.stack[--frame.sp].l);");
        if (spec.objectArray()) {
            context.nestedLine("env->SetObjectArrayElement(array, index, value);");
        } else {
            context.nestedLine("env->Set" + spec.jniName() + "ArrayRegion(array, index, 1, &value);");
        }
        context.line("}");
    }

    private boolean isLoad(IROpcode opcode) {
        return switch (opcode) {
            case IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD -> true;
            default -> false;
        };
    }

    private ArraySpec spec(IROpcode opcode) {
        return switch (opcode) {
            case IALOAD, IASTORE -> new ArraySpec("jintArray", "jint", "Int", "i", false);
            case LALOAD, LASTORE -> new ArraySpec("jlongArray", "jlong", "Long", "j", false);
            case FALOAD, FASTORE -> new ArraySpec("jfloatArray", "jfloat", "Float", "f", false);
            case DALOAD, DASTORE -> new ArraySpec("jdoubleArray", "jdouble", "Double", "d", false);
            case BALOAD, BASTORE -> new ArraySpec("jbyteArray", "jbyte", "Byte", "b", false);
            case CALOAD, CASTORE -> new ArraySpec("jcharArray", "jchar", "Char", "c", false);
            case SALOAD, SASTORE -> new ArraySpec("jshortArray", "jshort", "Short", "s", false);
            case AALOAD, AASTORE -> new ArraySpec("jobjectArray", "jobject", "Object", "l", true);
            default -> throw new IllegalArgumentException("Unsupported array opcode " + opcode);
        };
    }

    private record ArraySpec(String arrayType, String elementType, String jniName, String member, boolean objectArray) {
    }
}


