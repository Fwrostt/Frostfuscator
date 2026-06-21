package dev.frost.obfuscator.jni.core.ir;

import dev.frost.obfuscator.jni.core.model.LabelModel;

import java.util.List;
import java.util.Objects;

/**
 * Basic block container. The initial generator consumes the linear instruction
 * stream, while blocks preserve the planned CFG boundary.
 */
public record IRBlock(LabelModel label, List<IRInstruction> instructions) {
    public IRBlock {
        Objects.requireNonNull(label, "label");
        instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
    }
}


