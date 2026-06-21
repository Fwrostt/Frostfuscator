package dev.frost.obfuscator.jni;

import dev.frost.obfuscator.config.FrostJNIConfig;
import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.engine.JarProcessor;

import java.nio.file.Path;

public record NativeProtectionRequest(
        FrostJNIConfig config,
        ClassPool pool,
        JarProcessor processor,
        Path inputPath,
        Path outputPath
) {
}
