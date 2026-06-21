package dev.frost.obfuscator.jni;

import dev.frost.obfuscator.jni.compiler.CompilationResult;
import dev.frost.obfuscator.jni.compiler.CompilerInput;
import dev.frost.obfuscator.jni.generator.cpp.GeneratedCppClass;

import java.util.List;
import java.util.ServiceLoader;

public final class NativeProtectionHooks {
    private final List<NativeProtectionHook> hooks;

    public NativeProtectionHooks() {
        hooks = ServiceLoader.load(NativeProtectionHook.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    public void beforeNativeGeneration(NativeProtectionRequest request) {
        hooks.forEach(hook -> hook.beforeNativeGeneration(request));
    }

    public void afterNativeGeneration(NativeProtectionRequest request, List<GeneratedCppClass> generatedClasses) {
        hooks.forEach(hook -> hook.afterNativeGeneration(request, generatedClasses));
    }

    public void beforeCompilation(CompilerInput input) {
        hooks.forEach(hook -> hook.beforeCompilation(input, List.of()));
    }

    public void afterCompilation(CompilerInput input, CompilationResult result) {
        hooks.forEach(hook -> hook.afterCompilation(input, result));
    }
}
