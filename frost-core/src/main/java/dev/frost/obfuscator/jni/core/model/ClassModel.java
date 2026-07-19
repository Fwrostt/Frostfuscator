package dev.frost.obfuscator.jni.core.model;

import java.util.List;
import java.util.Objects;

/**
 * ASM-independent representation of a JVM class.
 *
 * @param internalName slash-separated JVM class name
 * @param superName slash-separated JVM super class name
 * @param access JVM access flags
 * @param methods methods declared by the class
 */
public record ClassModel(
        String internalName,
        String superName,
        int access,
        List<MethodModel> methods,
        List<String> annotationDescriptors
) {
    public ClassModel {
        Objects.requireNonNull(internalName, "internalName");
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        annotationDescriptors = List.copyOf(Objects.requireNonNull(annotationDescriptors, "annotationDescriptors"));
    }

    public ClassModel(String internalName, String superName, int access, List<MethodModel> methods) {
        this(internalName, superName, access, methods, List.of());
    }
}


