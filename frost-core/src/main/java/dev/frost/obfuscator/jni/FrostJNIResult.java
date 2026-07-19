package dev.frost.obfuscator.jni;

import dev.frost.obfuscator.jni.compiler.NativeLibrary;
import dev.frost.obfuscator.jni.patcher.NativeMethodPlan;

import java.nio.file.Path;
import java.util.List;

public record FrostJNIResult(
        int classesConverted,
        int methodsConverted,
        long nativeSourceBytes,
        long compilationTimeMs,
        String compilerUsed,
        Path workDirectory,
        List<NativeLibrary> generatedLibraries,
        List<String> excludedClasses,
        List<String> conversionFailures,
        List<NativeMethodPlan> nativeMethods
) {
}
