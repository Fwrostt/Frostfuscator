package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.IrType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A typed SSA value with exactly one definition and stable, explicit uses. */
public final class Value implements IrEntity {
    private final IrMethod method;
    private final IrId id;
    private final IrType type;
    private final ValueDefinition definition;
    private final int resultIndex;
    private final Set<Use> uses = new LinkedHashSet<>();
    private final MetadataMap metadata;
    private String debugName;

    Value(IrMethod method, IrId id, IrType type, ValueDefinition definition, int resultIndex) {
        this.method = Objects.requireNonNull(method, "method");
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.definition = Objects.requireNonNull(definition, "definition");
        if (resultIndex < 0) throw new IllegalArgumentException("resultIndex must be non-negative");
        this.resultIndex = resultIndex;
        metadata = new MetadataMap(method::touch);
    }

    @Override public IrMethod method() { return method; }
    @Override public IrId id() { return id; }
    @Override public MetadataMap metadata() { return metadata; }
    public IrType type() { return type; }
    public ValueDefinition definition() { return definition; }
    public int resultIndex() { return resultIndex; }
    public String debugName() { return debugName; }

    public void setDebugName(String name) {
        if (name != null && (name.isBlank() || !name.matches("[A-Za-z_$][A-Za-z0-9_$.-]*"))) {
            throw new IllegalArgumentException("Invalid value debug name: " + name);
        }
        if (!Objects.equals(debugName, name)) {
            debugName = name;
            method.touch();
        }
    }

    public Set<Use> uses() { return Collections.unmodifiableSet(uses); }
    public boolean isUsed() { return !uses.isEmpty(); }

    public void replaceAllUsesWith(Value replacement) {
        Objects.requireNonNull(replacement, "replacement");
        method.requireOwned(replacement);
        if (!type.equals(replacement.type)) {
            throw new IllegalArgumentException("Cannot replace " + type + " with " + replacement.type);
        }
        if (replacement == this) return;
        List<Use> snapshot = new ArrayList<>(uses);
        try (IrMethod.Mutation ignored = method.beginMutation("replace-all-uses")) {
            snapshot.forEach(use -> use.replaceWith(replacement));
        }
    }

    void attachUse(Use use) {
        if (!uses.add(use)) throw new IllegalStateException("Use already attached to value " + id);
    }

    void detachUse(Use use) {
        if (!uses.remove(use)) throw new IllegalStateException("Use not attached to value " + id);
    }

    @Override public String toString() { return "%" + (debugName == null ? id : debugName); }
}
