package dev.frost.ir.pass;

import dev.frost.ir.model.IrMethod;

/** Per-pipeline observer for diagnostics, profiling, graph dumps, and audit logs. */
public interface PassListener {
    default void beforePass(IrMethod method, MethodPass pass, long revision) {}
    default void afterPass(IrMethod method, MethodPass pass, PassExecution execution) {}
}
