package dev.frost.ir.model;

import java.util.Objects;

/** A stable operand edge in the value graph. */
public final class Use {
    private final ValueUser user;
    private final int index;
    private Value value;
    private boolean attached = true;

    Use(ValueUser user, int index, Value value) {
        this.user = Objects.requireNonNull(user, "user");
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
        this.index = index;
        this.value = Objects.requireNonNull(value, "value");
        user.method().requireOwned(value);
        value.attachUse(this);
    }

    public ValueUser user() { return user; }
    public int index() { return index; }
    public Value value() { return value; }
    public boolean isAttached() { return attached; }

    public void replaceWith(Value replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (!attached) throw new IllegalStateException("Cannot replace a detached use");
        user.method().requireOwned(replacement);
        if (replacement == value) return;
        Value previous = value;
        previous.detachUse(this);
        value = replacement;
        replacement.attachUse(this);
        user.method().touch();
    }

    void detach() {
        if (!attached) throw new IllegalStateException("Use already detached");
        value.detachUse(this);
        attached = false;
    }

    @Override public String toString() { return value + " -> " + user.id() + "[" + index + "]"; }
}
