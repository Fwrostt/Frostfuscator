package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.ReferenceType;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Ordered JVM exception-table region after instruction ranges have been split at block boundaries. */
public final class ExceptionRegion implements IrEntity {
    private final IrMethod method;
    private final IrId id;
    private final Set<BasicBlock> protectedBlocks;
    private final BasicBlock handler;
    private final ReferenceType catchType;
    private final int priority;
    private final MetadataMap metadata;

    ExceptionRegion(IrMethod method, IrId id, Set<BasicBlock> protectedBlocks, BasicBlock handler,
                    ReferenceType catchType, int priority) {
        this.method = Objects.requireNonNull(method, "method");
        this.id = Objects.requireNonNull(id, "id");
        this.protectedBlocks = new LinkedHashSet<>(Objects.requireNonNull(protectedBlocks, "protectedBlocks"));
        this.handler = Objects.requireNonNull(handler, "handler");
        this.catchType = catchType;
        this.priority = priority;
        if (protectedBlocks.isEmpty()) throw new IllegalArgumentException("exception region must protect at least one block");
        if (priority < 0) throw new IllegalArgumentException("priority must be non-negative");
        protectedBlocks.forEach(method::requireOwned);
        method.requireOwned(handler);
        metadata = new MetadataMap(method::touch);
    }

    @Override public IrMethod method() { return method; }
    @Override public IrId id() { return id; }
    @Override public MetadataMap metadata() { return metadata; }
    public Set<BasicBlock> protectedBlocks() { return Collections.unmodifiableSet(protectedBlocks); }
    public BasicBlock handler() { return handler; }
    public Optional<ReferenceType> catchType() { return Optional.ofNullable(catchType); }
    public boolean isFinally() { return catchType == null; }
    public int priority() { return priority; }

    void addProtectedBlock(BasicBlock block) {
        method.requireOwned(block);
        if (protectedBlocks.add(block)) method.touch();
    }

    void removeProtectedBlocks(Set<BasicBlock> blocks) { protectedBlocks.removeAll(blocks); }
}
