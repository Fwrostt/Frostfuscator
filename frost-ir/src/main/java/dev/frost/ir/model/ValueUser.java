package dev.frost.ir.model;

import java.util.List;

public interface ValueUser extends IrEntity {
    List<Use> operandUses();
}
