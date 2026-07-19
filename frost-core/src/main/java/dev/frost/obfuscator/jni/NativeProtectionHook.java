package dev.frost.obfuscator.jni;

import dev.frost.obfuscator.jni.compiler.CompilerCommand;
import dev.frost.obfuscator.jni.compiler.CompilationResult;
import dev.frost.obfuscator.jni.compiler.CompilerInput;
import dev.frost.obfuscator.jni.generator.cpp.GeneratedCppClass;

import java.util.List;

public interface NativeProtectionHook {
    default void beforeNativeGeneration(NativeProtectionRequest request) {
    }

    default void afterNativeGeneration(NativeProtectionRequest request, List<GeneratedCppClass> generatedClasses) {
    }

    default void beforeCompilation(CompilerInput input, List<CompilerCommand> commands) {
    }

    default void afterCompilation(CompilerInput input, CompilationResult result) {
    }
}
