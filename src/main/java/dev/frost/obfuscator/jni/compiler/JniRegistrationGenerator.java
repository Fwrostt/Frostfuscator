package dev.frost.obfuscator.jni.compiler;

import dev.frost.obfuscator.jni.core.descriptor.DescriptorParser;
import dev.frost.obfuscator.jni.core.descriptor.MethodDescriptor;
import dev.frost.obfuscator.jni.core.type.TypeUtils;
import dev.frost.obfuscator.jni.patcher.NativeMethodPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JniRegistrationGenerator {
    private final DescriptorParser descriptorParser = new DescriptorParser();
    private final TypeUtils typeUtils = new TypeUtils();

    public String generate(List<NativeMethodPlan> methods) {
        StringBuilder source = new StringBuilder();
        source.append("#include <jni.h>\n");
        source.append("#include <cstddef>\n\n");
        appendDeclarations(source, methods);
        appendOnLoad(source, methods);
        return source.toString();
    }

    private void appendDeclarations(StringBuilder source, List<NativeMethodPlan> methods) {
        for (NativeMethodPlan method : methods) {
            MethodDescriptor descriptor = descriptorParser.parseMethod(method.descriptor());
            source.append("extern \"C\" ")
                    .append(typeUtils.toJniType(descriptor.returnType()))
                    .append(" JNICALL ")
                    .append(method.nativeSymbol())
                    .append("(JNIEnv*, ")
                    .append(method.isStatic() ? "jclass" : "jobject");
            for (var parameter : descriptor.parameters()) {
                source.append(", ").append(typeUtils.toJniType(parameter));
            }
            source.append(");\n");
        }
        source.append('\n');
    }

    private void appendOnLoad(StringBuilder source, List<NativeMethodPlan> methods) {
        Map<String, List<NativeMethodPlan>> byClass = new LinkedHashMap<>();
        for (NativeMethodPlan method : methods) {
            byClass.computeIfAbsent(method.ownerInternalName(), ignored -> new ArrayList<>()).add(method);
        }

        source.append("extern \"C\" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {\n");
        source.append("    JNIEnv* env = nullptr;\n");
        source.append("    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || env == nullptr) {\n");
        source.append("        return JNI_ERR;\n");
        source.append("    }\n\n");

        int classIndex = 0;
        for (Map.Entry<String, List<NativeMethodPlan>> entry : byClass.entrySet()) {
            source.append("    {\n");
            source.append("        jclass clazz = env->FindClass(\"").append(escape(entry.getKey())).append("\");\n");
            source.append("        if (clazz == nullptr) {\n");
            source.append("            return JNI_ERR;\n");
            source.append("        }\n");
            source.append("        JNINativeMethod methods").append(classIndex).append("[] = {\n");
            for (NativeMethodPlan method : entry.getValue()) {
                source.append("            {const_cast<char*>(\"")
                        .append(escape(method.name()))
                        .append("\"), const_cast<char*>(\"")
                        .append(escape(method.descriptor()))
                        .append("\"), reinterpret_cast<void*>(")
                        .append(method.nativeSymbol())
                        .append(")},\n");
            }
            source.append("        };\n");
            source.append("        if (env->RegisterNatives(clazz, methods").append(classIndex)
                    .append(", static_cast<jint>(sizeof(methods").append(classIndex)
                    .append(") / sizeof(methods").append(classIndex).append("[0]))) != 0) {\n");
            source.append("            env->DeleteLocalRef(clazz);\n");
            source.append("            return JNI_ERR;\n");
            source.append("        }\n");
            source.append("        env->DeleteLocalRef(clazz);\n");
            source.append("    }\n\n");
            classIndex++;
        }
        source.append("    return JNI_VERSION_1_8;\n");
        source.append("}\n");
    }

    private String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
