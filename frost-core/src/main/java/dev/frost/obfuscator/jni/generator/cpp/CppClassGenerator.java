package dev.frost.obfuscator.jni.generator.cpp;

import dev.frost.obfuscator.jni.core.ir.IRClass;

/**
 * Compatibility facade for callers that used the original class-level
 * generator name.
 */
public final class CppClassGenerator {
    private final CppGenerator delegate = new CppGenerator();

    public GeneratedCppClass generate(IRClass irClass) {
        return delegate.generate(irClass);
    }
}


