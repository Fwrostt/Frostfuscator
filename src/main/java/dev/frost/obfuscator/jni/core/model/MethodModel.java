package dev.frost.obfuscator.jni.core.model;

import java.util.List;
import java.util.Objects;

/**
 * ASM-independent representation of a method body and metadata.
 */
public record MethodModel(
        String ownerInternalName,
        String name,
        String descriptor,
        int access,
        List<InstructionModel> instructions,
        List<TryCatchModel> tryCatchBlocks,
        List<String> annotationDescriptors
) {
    public MethodModel {
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
        tryCatchBlocks = List.copyOf(Objects.requireNonNull(tryCatchBlocks, "tryCatchBlocks"));
        annotationDescriptors = List.copyOf(Objects.requireNonNull(annotationDescriptors, "annotationDescriptors"));
    }

    public MethodModel(
            String ownerInternalName,
            String name,
            String descriptor,
            int access,
            List<InstructionModel> instructions,
            List<TryCatchModel> tryCatchBlocks
    ) {
        this(ownerInternalName, name, descriptor, access, instructions, tryCatchBlocks, List.of());
    }
}


