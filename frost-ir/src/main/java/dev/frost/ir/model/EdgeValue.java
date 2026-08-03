package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.IrType;
import java.util.Objects;
import java.util.Optional;

/**
 * A value that exists only while control traverses one CFG edge. Exceptional objects and future
 * branch-refined values use this definition kind without inventing executable source operations.
 */
public final class EdgeValue implements ValueDefinition {
    private final IrMethod method;
    private final IrId id;
    private final ControlEdge edge;
    private final String role;
    private final Value result;
    private final MetadataMap metadata;

    EdgeValue(IrMethod method, IrId id, ControlEdge edge, String role, IrType type) {
        this.method = Objects.requireNonNull(method, "method");
        this.id = Objects.requireNonNull(id, "id");
        this.edge = Objects.requireNonNull(edge, "edge");
        this.role = Objects.requireNonNull(role, "role");
        if (role.isBlank()) throw new IllegalArgumentException("edge value role must not be blank");
        metadata = new MetadataMap(method::touch);
        result = method.createValue(type, this, 0);
        result.setDebugName(role + "_e" + edge.id());
    }

    @Override public IrMethod method() { return method; }
    @Override public IrId id() { return id; }
    @Override public MetadataMap metadata() { return metadata; }
    @Override public Optional<BasicBlock> definingBlock() { return Optional.of(edge.source()); }
    public ControlEdge edge() { return edge; }
    public String role() { return role; }
    public Value result() { return result; }
}
