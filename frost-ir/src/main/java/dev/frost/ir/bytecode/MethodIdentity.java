package dev.frost.ir.bytecode;

import java.util.Objects;

/** Index is retained because hostile class files may contain duplicate name/descriptor pairs. */
public record MethodIdentity(int index, String name, String descriptor) {
    public MethodIdentity {
        if (index < 0) throw new IllegalArgumentException("method index must be non-negative");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
