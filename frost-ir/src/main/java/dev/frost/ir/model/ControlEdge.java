package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.ReferenceType;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A first-class CFG edge. Identity, not endpoint equality, keys phi inputs. */
public final class ControlEdge implements IrEntity {
    private final IrMethod method;
    private final IrId id;
    private final BasicBlock source;
    private final BasicBlock target;
    private final EdgeKind kind;
    private final String label;
    private final ReferenceType catchType;
    private final int priority;
    private final List<EdgeValue> values = new ArrayList<>();
    private final MetadataMap metadata;

    ControlEdge(IrMethod method, IrId id, BasicBlock source, BasicBlock target, EdgeKind kind,
                String label, ReferenceType catchType, int priority) {
        this.method = Objects.requireNonNull(method, "method");
        this.id = Objects.requireNonNull(id, "id");
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.label = label == null ? "" : label;
        this.catchType = catchType;
        this.priority = priority;
        if (priority < 0) throw new IllegalArgumentException("edge priority must be non-negative");
        if (kind.isExceptional() != (catchType != null || kind == EdgeKind.FINALLY)) {
            throw new IllegalArgumentException("catch type is only valid for exceptional edges");
        }
        metadata = new MetadataMap(method::touch);
    }

    @Override public IrMethod method() { return method; }
    @Override public IrId id() { return id; }
    @Override public MetadataMap metadata() { return metadata; }
    public BasicBlock source() { return source; }
    public BasicBlock target() { return target; }
    public EdgeKind kind() { return kind; }
    public String label() { return label; }
    public Optional<ReferenceType> catchType() { return Optional.ofNullable(catchType); }
    public int priority() { return priority; }
    public List<EdgeValue> values() { return Collections.unmodifiableList(values); }

    public EdgeValue addValue(String role, dev.frost.ir.type.IrType type) {
        EdgeValue value = new EdgeValue(method, method.nextId(), this, role, type);
        method.registerEntity(value);
        values.add(value);
        method.touch();
        return value;
    }

    @Override public String toString() { return source + " -" + kind + "-> " + target; }
}
