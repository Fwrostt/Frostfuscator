package dev.frost.obfuscator.jni.core.ir;

import java.util.List;
import java.util.Objects;

/**
 * Class-level IR for native code generation.
 */
public record IRClass(String internalName, String superName, int access, List<IRMethod> methods) {
    public IRClass {
        Objects.requireNonNull(internalName, "internalName");
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    }
}


