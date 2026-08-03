package dev.frost.ir.model;

import java.util.Optional;

/** The unique defining site of an SSA value. */
public interface ValueDefinition extends IrEntity {
    Optional<BasicBlock> definingBlock();
}
