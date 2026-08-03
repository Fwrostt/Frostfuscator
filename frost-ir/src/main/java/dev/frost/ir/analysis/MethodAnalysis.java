package dev.frost.ir.analysis;

import dev.frost.ir.model.IrMethod;

public interface MethodAnalysis<T> {
    AnalysisKey<T> key();
    T compute(IrMethod method, AnalysisManager analyses);
}
