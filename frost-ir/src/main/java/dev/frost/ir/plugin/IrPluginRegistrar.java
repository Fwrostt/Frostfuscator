package dev.frost.ir.plugin;

import dev.frost.ir.analysis.MethodAnalysis;
import dev.frost.ir.model.OperationSchema;
import dev.frost.ir.pass.MethodPass;
import java.util.function.Supplier;

/** Capabilities a plugin may add without gaining access to mutable global state. */
public interface IrPluginRegistrar {
    void registerOperation(OperationSchema schema);
    void registerAnalysis(MethodAnalysis<?> analysis);
    void registerPass(String id, Supplier<? extends MethodPass> factory);
}
