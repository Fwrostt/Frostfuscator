package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.descriptor.TypeDescriptor;
import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.core.model.FieldReference;
import dev.frost.obfuscator.jni.generator.cpp.CppStringEscaper;
import dev.frost.obfuscator.jni.generator.cpp.CppTranslationContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates JNI field reads and writes with runtime field ID caching.
 */
public final class FieldTranslator implements InstructionTranslator {
    private static final Set<IROpcode> OPCODES = EnumSet.of(IROpcode.GETSTATIC, IROpcode.PUTSTATIC, IROpcode.GETFIELD, IROpcode.PUTFIELD);

    @Override
    public boolean supports(IROpcode opcode) {
        return OPCODES.contains(opcode);
    }

    @Override
    public void translate(CppTranslationContext context, IRInstruction instruction) {
        FieldReference reference = (FieldReference) instruction.operands().get(0);
        TypeDescriptor fieldType = context.descriptorParser().parseField(reference.descriptor());
        String fieldKind = fieldKind(fieldType);
        boolean isStatic = instruction.opcode() == IROpcode.GETSTATIC || instruction.opcode() == IROpcode.PUTSTATIC;

        context.line("{");
        context.nestedLine("jclass clazz = " + context.classCache() + ".get(env, " + CppStringEscaper.quote(reference.ownerInternalName()) + ");");
        context.nestedLine("jfieldID field = " + context.fieldCache() + ".get(env, clazz, "
                + CppStringEscaper.quote(reference.ownerInternalName()) + ", "
                + CppStringEscaper.quote(reference.name()) + ", "
                + CppStringEscaper.quote(reference.descriptor()) + ", "
                + isStatic + ");");
        if (instruction.opcode() == IROpcode.GETSTATIC) {
            getStatic(context, fieldType, fieldKind);
        } else if (instruction.opcode() == IROpcode.PUTSTATIC) {
            putStatic(context, fieldType, fieldKind);
        } else if (instruction.opcode() == IROpcode.GETFIELD) {
            getField(context, fieldType, fieldKind);
        } else {
            putField(context, fieldType, fieldKind);
        }
        context.line("}");
    }

    private void getStatic(CppTranslationContext context, TypeDescriptor fieldType, String fieldKind) {
        context.nestedLine("jvalue result{};");
        context.nestedLine("result." + context.jvalueMember(fieldType) + " = env->GetStatic" + fieldKind + "Field(clazz, field);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
    }

    private void putStatic(CppTranslationContext context, TypeDescriptor fieldType, String fieldKind) {
        context.nestedLine(context.jniType(fieldType) + " value = " + context.stackValue(fieldType, "frame.stack[--frame.sp]") + ";");
        context.nestedLine("env->SetStatic" + fieldKind + "Field(clazz, field, value);");
    }

    private void getField(CppTranslationContext context, TypeDescriptor fieldType, String fieldKind) {
        context.nestedLine("jobject receiver = frame.stack[--frame.sp].l;");
        context.nestedLine("jvalue result{};");
        context.nestedLine("result." + context.jvalueMember(fieldType) + " = env->Get" + fieldKind + "Field(receiver, field);");
        context.nestedLine("frame.stack[frame.sp++] = result;");
    }

    private void putField(CppTranslationContext context, TypeDescriptor fieldType, String fieldKind) {
        context.nestedLine(context.jniType(fieldType) + " value = " + context.stackValue(fieldType, "frame.stack[--frame.sp]") + ";");
        context.nestedLine("jobject receiver = frame.stack[--frame.sp].l;");
        context.nestedLine("env->Set" + fieldKind + "Field(receiver, field, value);");
    }

    private String fieldKind(TypeDescriptor fieldType) {
        return switch (fieldType.valueType()) {
            case BOOLEAN -> "Boolean";
            case BYTE -> "Byte";
            case CHAR -> "Char";
            case SHORT -> "Short";
            case INT -> "Int";
            case LONG -> "Long";
            case FLOAT -> "Float";
            case DOUBLE -> "Double";
            case ARRAY, OBJECT -> "Object";
            case VOID -> throw new IllegalArgumentException("Fields cannot be void");
        };
    }
}



