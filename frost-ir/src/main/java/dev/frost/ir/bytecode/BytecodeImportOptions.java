package dev.frost.ir.bytecode;

public record BytecodeImportOptions(boolean splitPotentiallyThrowingInstructions,
                                    boolean preserveUnreachableCode,
                                    boolean validateResult) {
    public static BytecodeImportOptions defaults() {
        return new BytecodeImportOptions(true, true, true);
    }
}
