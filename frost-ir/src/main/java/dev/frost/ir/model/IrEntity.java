package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;

public interface IrEntity {
    IrId id();
    IrMethod method();
    MetadataMap metadata();
}
