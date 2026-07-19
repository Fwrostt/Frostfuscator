package dev.frost.obfuscator.jni.generator.cpp;

import java.util.Objects;

/**
 * Generated C++ translation unit for a single Java class.
 */
public record GeneratedCppClass(String fileName, String source) {
    public GeneratedCppClass {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(source, "source");
    }
}


