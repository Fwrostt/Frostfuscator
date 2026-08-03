package dev.frost.ir.analysis;

/** A memory state that can reach a use or another definition. */
public sealed interface MemoryVersion extends MemoryAccess permits MemoryLiveOnEntry,
        MemoryPhi, MemoryDef, MemoryEdgeState {}
