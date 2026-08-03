package dev.frost.ir.analysis;

/** Analysis-local node in the MemorySSA graph. */
public sealed interface MemoryAccess permits MemoryUse, MemoryVersion {
    long id();
}
