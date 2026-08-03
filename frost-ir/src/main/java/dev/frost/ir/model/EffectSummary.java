package dev.frost.ir.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class EffectSummary {
    public static final EffectSummary PURE = new EffectSummary(EnumSet.noneOf(Effect.class));
    public static final EffectSummary UNKNOWN = new EffectSummary(EnumSet.of(Effect.UNKNOWN, Effect.MAY_THROW));

    private final Set<Effect> effects;

    private EffectSummary(EnumSet<Effect> effects) {
        this.effects = Collections.unmodifiableSet(effects);
    }

    public static EffectSummary of(Effect first, Effect... rest) {
        Objects.requireNonNull(first, "first");
        EnumSet<Effect> values = EnumSet.of(first, rest);
        return new EffectSummary(values);
    }

    public static EffectSummary copyOf(Set<Effect> effects) {
        Objects.requireNonNull(effects, "effects");
        return effects.isEmpty() ? PURE : new EffectSummary(EnumSet.copyOf(effects));
    }

    public Set<Effect> effects() { return effects; }
    public boolean has(Effect effect) { return effects.contains(effect); }
    public boolean isPure() { return effects.isEmpty(); }

    public EffectSummary union(EffectSummary other) {
        if (other.effects.isEmpty()) return this;
        if (effects.isEmpty()) return other;
        EnumSet<Effect> merged = EnumSet.copyOf(effects);
        merged.addAll(other.effects);
        return new EffectSummary(merged);
    }

    @Override public boolean equals(Object other) {
        return other instanceof EffectSummary summary && effects.equals(summary.effects);
    }
    @Override public int hashCode() { return effects.hashCode(); }
    @Override public String toString() { return effects.toString(); }
}
