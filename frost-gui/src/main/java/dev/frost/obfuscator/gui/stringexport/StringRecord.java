package dev.frost.obfuscator.gui.stringexport;

/**
 * Data record representing an extracted string occurrence and its analysis metadata.
 */
public record StringRecord(
        String value,
        String decodedValue,
        String className,
        String methodName,
        String methodDescriptor,
        int instructionIndex,
        String sourceType,
        String category,
        double entropy,
        int frequency,
        boolean likelyEncoded
) {}
