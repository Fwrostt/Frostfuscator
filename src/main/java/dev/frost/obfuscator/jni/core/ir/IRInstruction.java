package dev.frost.obfuscator.jni.core.ir;

import java.util.List;
import java.util.Objects;

/**
 * Backend-neutral bytecode-like instruction.
 */
public record IRInstruction(IROpcode opcode, List<Object> operands) {
    public IRInstruction {
        Objects.requireNonNull(opcode, "opcode");
        operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
    }

    public IRInstruction(IROpcode opcode) {
        this(opcode, List.of());
    }
}


