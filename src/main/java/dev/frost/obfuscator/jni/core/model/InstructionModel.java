package dev.frost.obfuscator.jni.core.model;

import java.util.List;
import java.util.Objects;

/**
 * One bytecode instruction after parsing. Operands use simple JVM values:
 * integers, strings, {@link LabelModel}, {@link MethodReference}, or
 * {@link FieldReference}.
 */
public record InstructionModel(
        int opcode,
        String mnemonic,
        InstructionKind kind,
        List<Object> operands
) {
    public InstructionModel {
        Objects.requireNonNull(mnemonic, "mnemonic");
        Objects.requireNonNull(kind, "kind");
        operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
    }

    public static InstructionModel label(LabelModel label) {
        return new InstructionModel(-1, "LABEL", InstructionKind.LABEL, List.of(label));
    }
}


