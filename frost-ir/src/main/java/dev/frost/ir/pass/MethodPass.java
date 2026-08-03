package dev.frost.ir.pass;

import dev.frost.ir.model.IrMethod;

public interface MethodPass {
    String id();
    PassResult run(IrMethod method, PassContext context);
}
