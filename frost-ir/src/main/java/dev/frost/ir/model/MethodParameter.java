package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.IrType;
import java.util.Objects;
import java.util.Optional;

public final class MethodParameter implements ValueDefinition {
    private final IrMethod method;
    private final IrId id;
    private final int index;
    private final String name;
    private final Value value;
    private final MetadataMap metadata;

    MethodParameter(IrMethod method, IrId id, int index, String name, IrType type) {
        this.method = Objects.requireNonNull(method, "method");
        this.id = Objects.requireNonNull(id, "id");
        this.index = index;
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank() || !name.matches("[A-Za-z_$][A-Za-z0-9_$.-]*")) {
            throw new IllegalArgumentException("Invalid parameter name: " + name);
        }
        metadata = new MetadataMap(method::touch);
        value = method.createValue(type, this, 0);
        value.setDebugName(name);
    }

    @Override public IrMethod method() { return method; }
    @Override public IrId id() { return id; }
    @Override public MetadataMap metadata() { return metadata; }
    @Override public Optional<BasicBlock> definingBlock() { return Optional.empty(); }
    public int index() { return index; }
    public String name() { return name; }
    public Value value() { return value; }
}
