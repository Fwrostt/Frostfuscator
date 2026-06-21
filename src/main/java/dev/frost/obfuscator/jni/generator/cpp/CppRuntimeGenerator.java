package dev.frost.obfuscator.jni.generator.cpp;

/**
 * Emits the common prelude required by generated C++ translation units.
 */
public final class CppRuntimeGenerator {
    public void appendPrelude(StringBuilder source, String cacheSuffix) {
        source.append("#include <jni.h>\n");
        source.append("#include <cmath>\n");
        source.append("#include \"frostjni_runtime.hpp\"\n\n");
        source.append("static frostjni::ClassCache frostClassCache_").append(cacheSuffix).append(";\n");
        source.append("static frostjni::MethodCache frostMethodCache_").append(cacheSuffix).append(";\n");
        source.append("static frostjni::FieldCache frostFieldCache_").append(cacheSuffix).append(";\n\n");
    }
}


