package dev.frost.ir.model;

import java.util.List;

@FunctionalInterface
public interface OperationVerifier {
    List<OperationViolation> verify(IrInstruction instruction);
}
