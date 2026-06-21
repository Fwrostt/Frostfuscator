package dev.frost.obfuscator.jni.core.ir;

import java.util.List;
import java.util.Objects;

/**
 * Method IR plus original JVM metadata needed for JNI generation.
 */
public record IRMethod(
        String ownerInternalName,
        String name,
        String descriptor,
        int access,
        List<IRInstruction> instructions,
        List<IRBlock> blocks
) {
    public IRMethod {
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    }
}


