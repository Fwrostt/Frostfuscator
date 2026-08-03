package dev.frost.ir.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

/** Declarative structural and effect contract for an operation code. */
public final class OperationSchema {
    private final OperationCode code;
    private final int minOperands;
    private final int maxOperands;
    private final int minResults;
    private final int maxResults;
    private final Set<OperationTrait> traits;
    private final EffectSummary effects;
    private final List<OperationVerifier> verifiers;

    private OperationSchema(Builder builder) {
        code = builder.code;
        minOperands = builder.minOperands;
        maxOperands = builder.maxOperands;
        minResults = builder.minResults;
        maxResults = builder.maxResults;
        traits = Collections.unmodifiableSet(builder.traits.clone());
        effects = builder.effects;
        verifiers = List.copyOf(builder.verifiers);
    }

    public static Builder builder(OperationCode code) { return new Builder(code); }
    public OperationCode code() { return code; }
    public int minOperands() { return minOperands; }
    public int maxOperands() { return maxOperands; }
    public int minResults() { return minResults; }
    public int maxResults() { return maxResults; }
    public Set<OperationTrait> traits() { return traits; }
    public EffectSummary effects() { return effects; }
    public List<OperationVerifier> verifiers() { return verifiers; }
    public boolean hasTrait(OperationTrait trait) { return traits.contains(trait); }
    public boolean acceptsOperandCount(int count) { return count >= minOperands && count <= maxOperands; }
    public boolean acceptsResultCount(int count) { return count >= minResults && count <= maxResults; }

    public static final class Builder {
        private final OperationCode code;
        private int minOperands;
        private int maxOperands;
        private int minResults;
        private int maxResults;
        private EnumSet<OperationTrait> traits = EnumSet.noneOf(OperationTrait.class);
        private EffectSummary effects = EffectSummary.PURE;
        private final List<OperationVerifier> verifiers = new ArrayList<>();

        private Builder(OperationCode code) { this.code = Objects.requireNonNull(code, "code"); }

        public Builder operands(int exact) { return operands(exact, exact); }
        public Builder operands(int min, int max) {
            if (min < 0 || max < min) throw new IllegalArgumentException("invalid operand bounds");
            minOperands = min; maxOperands = max; return this;
        }
        public Builder variadicOperands(int min) { return operands(min, Integer.MAX_VALUE); }
        public Builder results(int exact) { return results(exact, exact); }
        public Builder results(int min, int max) {
            if (min < 0 || max < min) throw new IllegalArgumentException("invalid result bounds");
            minResults = min; maxResults = max; return this;
        }
        public Builder traits(OperationTrait... values) {
            Collections.addAll(traits, values); return this;
        }
        public Builder effects(EffectSummary value) { effects = Objects.requireNonNull(value, "effects"); return this; }
        public Builder verify(OperationVerifier... values) {
            for (OperationVerifier value : values) verifiers.add(Objects.requireNonNull(value, "verifier"));
            return this;
        }
        public OperationSchema build() { return new OperationSchema(this); }
    }
}
