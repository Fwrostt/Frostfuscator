package dev.frost.ir.analysis;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.PrimitiveType;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Signed integer interval analysis with overflow-safe transfer functions and loop widening. */
public final class IntegerRangeAnalysis {
    private final IrMethod method;
    private final Map<Value, IntegerRange> facts = new IdentityHashMap<>();
    private final Map<Value, Integer> expansions = new IdentityHashMap<>();

    private IntegerRangeAnalysis(IrMethod method) {
        this.method = method;
        method.parameters().forEach(parameter -> {
            if (integral(parameter.value())) facts.put(parameter.value(), full(parameter.value()));
        });
        solve();
    }

    public static IntegerRangeAnalysis compute(IrMethod method) {
        return new IntegerRangeAnalysis(Objects.requireNonNull(method, "method"));
    }

    public Optional<IntegerRange> range(Value value) {
        method.requireOwned(value);
        return Optional.ofNullable(facts.get(value));
    }

    private void solve() {
        boolean changed;
        do {
            changed = false;
            for (var block : method.blocks()) {
                for (PhiNode phi : block.phis()) {
                    if (!integral(phi.result())) continue;
                    IntegerRange candidate = null;
                    for (Value input : phi.inputs().values()) {
                        IntegerRange incoming = facts.get(input);
                        if (incoming != null) candidate = candidate == null ? incoming : candidate.union(incoming);
                    }
                    if (candidate != null) changed |= update(phi.result(), candidate);
                }
                for (IrInstruction instruction : block.instructions()) {
                    if (instruction.results().size() != 1 || !integral(instruction.result())) continue;
                    IntegerRange candidate = evaluate(instruction);
                    if (candidate != null) changed |= update(instruction.result(), candidate);
                }
            }
        } while (changed);
    }

    private IntegerRange evaluate(IrInstruction instruction) {
        var code = instruction.operation().code();
        if (code.equals(CoreOps.CONSTANT)
                && instruction.operation().attributes().get("value") instanceof IrAttribute.LongValue value) {
            return IntegerRange.exact(normalize(instruction.result(), value.value()));
        }
        if (code.equals(CoreOps.COMPARE)) return new IntegerRange(-1, 1);
        if (code.equals(CoreOps.INSTANCE_OF)) return new IntegerRange(0, 1);
        if (code.equals(CoreOps.COPY) && !instruction.operands().isEmpty()) return facts.get(instruction.operands().getFirst());
        if (code.equals(CoreOps.SELECT) && instruction.operands().size() == 3) {
            IntegerRange left = facts.get(instruction.operands().get(1)), right = facts.get(instruction.operands().get(2));
            return left == null ? right : right == null ? left : left.union(right);
        }
        if (code.equals(CoreOps.NEG)) {
            IntegerRange input = facts.get(instruction.operands().getFirst());
            if (input == null || input.minimum() == minimum(instruction.result())) return full(instruction.result());
            return new IntegerRange(-input.maximum(), -input.minimum());
        }
        if (code.equals(CoreOps.CONVERT)) {
            IntegerRange input = facts.get(instruction.operands().getFirst());
            if (input == null) return null;
            IntegerRange target = full(instruction.result());
            return input.minimum() >= target.minimum() && input.maximum() <= target.maximum() ? input : target;
        }
        if (instruction.operands().size() != 2) return full(instruction.result());
        IntegerRange left = facts.get(instruction.operands().get(0)), right = facts.get(instruction.operands().get(1));
        if (left == null || right == null) return null;
        if (code.equals(CoreOps.ADD)) return bounded(instruction.result(), big(left.minimum()).add(big(right.minimum())),
                big(left.maximum()).add(big(right.maximum())));
        if (code.equals(CoreOps.SUB)) return bounded(instruction.result(), big(left.minimum()).subtract(big(right.maximum())),
                big(left.maximum()).subtract(big(right.minimum())));
        if (code.equals(CoreOps.MUL)) {
            BigInteger[] products = { big(left.minimum()).multiply(big(right.minimum())),
                    big(left.minimum()).multiply(big(right.maximum())), big(left.maximum()).multiply(big(right.minimum())),
                    big(left.maximum()).multiply(big(right.maximum())) };
            BigInteger low = products[0], high = products[0];
            for (BigInteger value : products) { if (value.compareTo(low) < 0) low = value; if (value.compareTo(high) > 0) high = value; }
            return bounded(instruction.result(), low, high);
        }
        if (code.equals(CoreOps.AND) && left.minimum() >= 0 && right.minimum() >= 0) {
            return new IntegerRange(0, Math.min(left.maximum(), right.maximum()));
        }
        return full(instruction.result());
    }

    private boolean update(Value value, IntegerRange candidate) {
        IntegerRange previous = facts.get(value);
        if (previous == null) { facts.put(value, candidate); return true; }
        IntegerRange merged = previous.union(candidate);
        if (merged.equals(previous)) return false;
        int count = expansions.merge(value, 1, Integer::sum);
        if (count > 2) {
            long low = merged.minimum() < previous.minimum() ? minimum(value) : merged.minimum();
            long high = merged.maximum() > previous.maximum() ? maximum(value) : merged.maximum();
            merged = new IntegerRange(low, high);
        }
        facts.put(value, merged);
        return true;
    }

    private IntegerRange bounded(Value value, BigInteger low, BigInteger high) {
        BigInteger min = big(minimum(value)), max = big(maximum(value));
        if (low.compareTo(min) < 0 || high.compareTo(max) > 0) return full(value);
        return new IntegerRange(low.longValue(), high.longValue());
    }

    private boolean integral(Value value) {
        return value.type() instanceof PrimitiveType primitive && switch (primitive) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT, LONG -> true;
            default -> false;
        };
    }
    private long normalize(Value value, long number) {
        PrimitiveType type = (PrimitiveType) value.type();
        return switch (type) {
            case BOOLEAN -> number == 0 ? 0 : 1;
            case BYTE -> (byte) number;
            case CHAR -> (char) number;
            case SHORT -> (short) number;
            case INT -> (int) number;
            case LONG -> number;
            default -> throw new IllegalArgumentException("not integral");
        };
    }
    private IntegerRange full(Value value) { return new IntegerRange(minimum(value), maximum(value)); }
    private long minimum(Value value) {
        return switch ((PrimitiveType) value.type()) {
            case BOOLEAN, CHAR -> 0; case BYTE -> Byte.MIN_VALUE; case SHORT -> Short.MIN_VALUE;
            case INT -> Integer.MIN_VALUE; case LONG -> Long.MIN_VALUE; default -> throw new IllegalArgumentException("not integral");
        };
    }
    private long maximum(Value value) {
        return switch ((PrimitiveType) value.type()) {
            case BOOLEAN -> 1; case BYTE -> Byte.MAX_VALUE; case CHAR -> Character.MAX_VALUE;
            case SHORT -> Short.MAX_VALUE; case INT -> Integer.MAX_VALUE; case LONG -> Long.MAX_VALUE;
            default -> throw new IllegalArgumentException("not integral");
        };
    }
    private BigInteger big(long value) { return BigInteger.valueOf(value); }
}
