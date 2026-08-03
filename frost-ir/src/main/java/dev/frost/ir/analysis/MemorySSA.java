package dev.frost.ir.analysis;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.Effect;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Edge-exact MemorySSA graph. Explicit entry phis on non-entry blocks favor correctness and stable
 * incremental APIs; exceptional edges use conservative barriers until instruction-granular EH CFGs
 * are requested by a future normalization pass.
 */
public final class MemorySSA {
    private final IrMethod method;
    private final AliasAnalysis aliases;
    private final MemoryLiveOnEntry liveOnEntry;
    private final Map<BasicBlock, MemoryVersion> entries = new IdentityHashMap<>();
    private final Map<BasicBlock, MemoryVersion> exits = new IdentityHashMap<>();
    private final Map<IrInstruction, MemoryAccess> accesses = new IdentityHashMap<>();
    private final List<MemoryAccess> order = new ArrayList<>();
    private long nextId;

    private MemorySSA(IrMethod method, AliasAnalysis aliases) {
        this.method = method;
        this.aliases = aliases;
        liveOnEntry = new MemoryLiveOnEntry(nextId++);
        order.add(liveOnEntry);
        build();
    }

    public static MemorySSA compute(IrMethod method, AliasAnalysis aliases) {
        return new MemorySSA(Objects.requireNonNull(method, "method"), Objects.requireNonNull(aliases, "aliases"));
    }

    public MemoryLiveOnEntry liveOnEntry() { return liveOnEntry; }
    public List<MemoryAccess> accesses() { return List.copyOf(order); }

    public MemoryVersion entryVersion(BasicBlock block) {
        method.requireOwned(block);
        return entries.get(block);
    }

    public MemoryVersion exitVersion(BasicBlock block) {
        method.requireOwned(block);
        return exits.get(block);
    }

    public Optional<MemoryAccess> access(IrInstruction instruction) {
        method.requireOwned(instruction);
        return Optional.ofNullable(accesses.get(instruction));
    }

    /** Finds the nearest aliasing definition, or a phi/barrier when paths cannot be collapsed. */
    public MemoryVersion clobberingAccess(MemoryUse use) {
        Objects.requireNonNull(use, "use");
        if (!order.contains(use)) throw new IllegalArgumentException("Memory use belongs to another analysis");
        return clobber(use.reachingVersion(), use.location(),
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private void build() {
        BasicBlock entry = method.entryBlock().orElse(null);
        for (BasicBlock block : method.blocks()) {
            if (block == entry) entries.put(block, liveOnEntry);
            else {
                MemoryPhi phi = new MemoryPhi(nextId++, block);
                entries.put(block, phi);
                order.add(phi);
            }
        }
        for (BasicBlock block : method.blocks()) {
            MemoryVersion current = entries.get(block);
            for (IrInstruction instruction : block.instructions()) {
                MemoryLocation location = aliases.location(instruction).orElse(MemoryLocation.Unknown.INSTANCE);
                if (definesMemory(instruction)) {
                    MemoryDef definition = new MemoryDef(nextId++, instruction, current, location);
                    accesses.put(instruction, definition);
                    order.add(definition);
                    current = definition;
                } else if (readsMemory(instruction)) {
                    MemoryUse use = new MemoryUse(nextId++, instruction, current, location);
                    accesses.put(instruction, use);
                    order.add(use);
                }
            }
            exits.put(block, current);
        }
        for (BasicBlock block : method.blocks()) {
            if (!(entries.get(block) instanceof MemoryPhi phi)) continue;
            for (ControlEdge edge : block.incomingEdges()) {
                MemoryVersion incoming = exits.getOrDefault(edge.source(), liveOnEntry);
                if (edge.kind().isExceptional()) {
                    MemoryEdgeState barrier = new MemoryEdgeState(nextId++, edge, incoming);
                    order.add(barrier);
                    incoming = barrier;
                }
                phi.putInput(edge, incoming);
            }
            phi.freeze();
        }
    }

    private MemoryVersion clobber(MemoryVersion version, MemoryLocation location, Set<MemoryVersion> visiting) {
        if (!visiting.add(version)) return version;
        try {
            if (version instanceof MemoryLiveOnEntry || version instanceof MemoryEdgeState) return version;
            if (version instanceof MemoryDef definition) {
                return aliases.mayAlias(definition.location(), location)
                        ? definition : clobber(definition.previous(), location, visiting);
            }
            MemoryPhi phi = (MemoryPhi) version;
            MemoryVersion common = null;
            for (MemoryVersion input : phi.inputs().values()) {
                MemoryVersion candidate = clobber(input, location, visiting);
                if (common == null) common = candidate;
                else if (common != candidate) return phi;
            }
            return common == null ? liveOnEntry : common;
        } finally {
            visiting.remove(version);
        }
    }

    private boolean readsMemory(IrInstruction instruction) {
        var effects = instruction.effects();
        return effects.has(Effect.READ_HEAP) || effects.has(Effect.READ_STATIC) || effects.has(Effect.READ_ARRAY);
    }

    private boolean definesMemory(IrInstruction instruction) {
        var effects = instruction.effects();
        if (instruction.operation().code().equals(CoreOps.STATIC_LOAD)) return true; // JVM class-init barrier.
        return effects.has(Effect.WRITE_HEAP) || effects.has(Effect.WRITE_STATIC) || effects.has(Effect.WRITE_ARRAY)
                || effects.has(Effect.ALLOCATE) || effects.has(Effect.INVOKE) || effects.has(Effect.DYNAMIC_LINKAGE)
                || effects.has(Effect.MONITOR) || effects.has(Effect.VOLATILE) || effects.has(Effect.NATIVE)
                || effects.has(Effect.IO) || effects.has(Effect.UNKNOWN);
    }
}
