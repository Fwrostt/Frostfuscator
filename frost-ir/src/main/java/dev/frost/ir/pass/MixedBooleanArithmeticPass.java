package dev.frost.ir.pass;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.Value;
import dev.frost.ir.transform.IrExpressionBuilder;
import dev.frost.ir.type.PrimitiveType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/** Mixed Boolean-arithmetic identities over typed SSA def-use chains. */
public final class MixedBooleanArithmeticPass implements MethodPass {
    public static final String ID = "frost.obfuscate.mba";

    private final Options options;

    public MixedBooleanArithmeticPass(Options options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (options.maximum() == 0 || options.probability() == 0) return PassResult.unchanged();
        SplittableRandom random = context.randomFor(id());
        List<IrInstruction> candidates = method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::candidate)
                .toList();
        int instructionCount = instructionCount(method);
        int arithmetic = 0;
        int conditions = 0;
        int generated = 0;

        for (IrInstruction instruction : candidates) {
            if (arithmetic + conditions >= options.maximum()
                    || instructionCount >= options.maximumInstructions()
                    || random.nextInt(100) >= options.probability()) continue;
            OperationCode code = instruction.operation().code();
            int before = instruction.method().blocks().stream()
                    .mapToInt(block -> block.instructions().size()).sum();
            if (isEnabledArithmetic(instruction)) {
                Value replacement = rewriteArithmetic(method, instruction, random);
                if (replacement == null) continue;
                copyIdentity(instruction, replacement);
                instruction.result().replaceAllUsesWith(replacement);
                instruction.erase();
                arithmetic++;
            } else if (code.equals(CoreOps.CONDITIONAL_BRANCH) && options.conditionals()) {
                if (rewriteOperands(method, instruction, random)) conditions++;
            } else if (code.equals(CoreOps.SWITCH) && options.switchKeys()) {
                if (rewriteOperands(method, instruction, random)) conditions++;
            } else if (code.equals(CoreOps.COMPARE) && options.longComparisons()
                    && instruction.operands().stream().allMatch(value -> value.type() == PrimitiveType.LONG)) {
                if (rewriteOperands(method, instruction, random)) conditions++;
            }
            int after = instruction.method().blocks().stream()
                    .mapToInt(block -> block.instructions().size()).sum();
            generated += Math.max(0, after - before);
            instructionCount = after;
        }

        if (arithmetic + conditions == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of(
                "arithmetic", (long) arithmetic,
                "conditionals", (long) conditions,
                "generated_operations", (long) generated));
    }

    private int instructionCount(IrMethod method) {
        return method.blocks().stream().mapToInt(block -> block.instructions().size()).sum();
    }

    private boolean candidate(IrInstruction instruction) {
        OperationCode code = instruction.operation().code();
        return isEnabledArithmetic(instruction)
                || options.conditionals() && code.equals(CoreOps.CONDITIONAL_BRANCH)
                || options.switchKeys() && code.equals(CoreOps.SWITCH)
                || options.longComparisons() && code.equals(CoreOps.COMPARE);
    }

    private boolean isEnabledArithmetic(IrInstruction instruction) {
        if (instruction.results().size() != 1 || !integerType(instruction.result())) return false;
        OperationCode code = instruction.operation().code();
        if (code.equals(CoreOps.NEG)) return options.operations().contains("neg")
                && instruction.operands().size() == 1;
        if (instruction.operands().size() != 2
                || instruction.operands().stream().anyMatch(value -> !value.type().equals(instruction.result().type()))) {
            return false;
        }
        if (code.equals(CoreOps.ADD)) return options.operations().contains("add");
        if (code.equals(CoreOps.SUB)) return options.operations().contains("sub");
        if (code.equals(CoreOps.MUL)) return options.operations().contains("mul");
        if (code.equals(CoreOps.AND)) return options.operations().contains("and");
        if (code.equals(CoreOps.OR)) return options.operations().contains("or");
        return code.equals(CoreOps.XOR) && options.operations().contains("xor");
    }

    private Value rewriteArithmetic(IrMethod method, IrInstruction instruction, SplittableRandom random) {
        BasicBlock block = instruction.block().orElseThrow();
        IrExpressionBuilder builder = new IrExpressionBuilder(method, block,
                block.instructions().indexOf(instruction));
        PrimitiveType type = (PrimitiveType) instruction.result().type();
        List<Value> operands = instruction.operands();
        Value x = operands.getFirst();
        Value y = operands.size() == 1 ? x : operands.get(1);
        OperationCode code = instruction.operation().code();
        Value base;
        if (code.equals(CoreOps.ADD)) base = addIdentity(builder, x, y, type, random.nextInt(3));
        else if (code.equals(CoreOps.SUB)) {
            Value negative = negateIdentity(builder, y, type, random.nextInt(3));
            base = addIdentity(builder, x, negative, type, random.nextInt(3));
        } else if (code.equals(CoreOps.MUL)) base = builder.binary(CoreOps.MUL, x, y, type);
        else if (code.equals(CoreOps.AND)) base = andIdentity(builder, x, y, type, random.nextInt(3));
        else if (code.equals(CoreOps.OR)) base = orIdentity(builder, x, y, type, random.nextInt(3));
        else if (code.equals(CoreOps.XOR)) base = xorIdentity(builder, x, y, type, random.nextInt(3));
        else if (code.equals(CoreOps.NEG)) base = negateIdentity(builder, x, type, random.nextInt(3));
        else return null;
        return decorate(builder, base, x, y, type, random);
    }

    private boolean rewriteOperands(IrMethod method, IrInstruction instruction, SplittableRandom random) {
        List<Value> original = new ArrayList<>(instruction.operands());
        boolean changed = false;
        for (int operandIndex = 0; operandIndex < original.size(); operandIndex++) {
            Value value = original.get(operandIndex);
            if (!integerType(value)) continue;
            PrimitiveType type = (PrimitiveType) value.type();
            Value anchor = original.stream().filter(other -> other != value && other.type().equals(type))
                    .findFirst().orElse(value);
            BasicBlock block = instruction.block().orElseThrow();
            IrExpressionBuilder builder = new IrExpressionBuilder(method, block,
                    block.instructions().indexOf(instruction));
            Value zero = random.nextBoolean()
                    ? builder.binary(CoreOps.SUB, anchor, anchor, type)
                    : builder.binary(CoreOps.XOR, anchor, anchor, type);
            Value identity = random.nextBoolean()
                    ? builder.binary(CoreOps.ADD, value, zero, type)
                    : builder.binary(CoreOps.XOR, value, zero, type);
            Value decorated = decorate(builder, identity, value, anchor, type, random);
            instruction.setOperand(operandIndex, decorated);
            changed = true;
        }
        return changed;
    }

    private Value decorate(IrExpressionBuilder builder, Value value, Value x, Value y,
                           PrimitiveType type, SplittableRandom random) {
        Value result = value;
        for (int round = 0; round < options.rounds(); round++) {
            long multiplier = randomOdd(random, type);
            long inverse = modularInverse(multiplier, type);
            long mask = randomValue(random, type);
            Value multiplierValue = builder.constant(multiplier, type);
            Value product = builder.binary(CoreOps.MUL, multiplierValue, result, type);
            Value maskValue = builder.constant(mask, type);
            Value masked = builder.binary(CoreOps.ADD, product, maskValue, type);
            Value negativeMask = builder.constant(negate(mask, type), type);
            Value unmasked = builder.binary(CoreOps.ADD, masked, negativeMask, type);
            Value inverseValue = builder.constant(inverse, type);
            result = builder.binary(CoreOps.MUL, inverseValue, unmasked, type);

            for (int term = 0; term < options.zeroTerms(); term++) {
                Value zeroPolynomial = zeroPolynomial(builder, x, y, type, random,
                        options.polynomialDegree(), round + term);
                result = builder.binary(CoreOps.ADD, result, zeroPolynomial, type);
            }
        }
        return result;
    }

    private Value zeroPolynomial(IrExpressionBuilder builder, Value x, Value y,
                                 PrimitiveType type, SplittableRandom random,
                                 int degree, int salt) {
        Value coefficient = polynomial(builder, x, y, type, random, degree);
        return switch (Math.floorMod(random.nextInt() + salt, 4)) {
            case 0 -> {
                Value identity = addIdentity(builder, x, y, type, random.nextInt(3));
                Value direct = builder.binary(CoreOps.ADD, x, y, type);
                Value zero = builder.binary(CoreOps.SUB, identity, direct, type);
                yield builder.binary(CoreOps.MUL, coefficient, zero, type);
            }
            case 1 -> {
                Value xor = builder.binary(CoreOps.XOR, x, y, type);
                Value u = builder.binary(CoreOps.ADD, coefficient, xor, type);
                Value allBits = builder.binary(CoreOps.OR, u, bitwiseNot(builder, u, type), type);
                Value zero = builder.binary(CoreOps.ADD, allBits, builder.constant(1, type), type);
                Value factor = polynomial(builder, y, x, type, random, degree);
                yield builder.binary(CoreOps.MUL, factor, zero, type);
            }
            case 2 -> {
                Value notY = bitwiseNot(builder, y, type);
                Value maskedX = builder.binary(CoreOps.AND, x, notY, type);
                Value u = builder.binary(CoreOps.ADD, coefficient, maskedX, type);
                Value successor = builder.binary(CoreOps.ADD, u, builder.constant(1, type), type);
                Value even = builder.binary(CoreOps.MUL, u, successor, type);
                Value zero = builder.binary(CoreOps.AND, even, builder.constant(1, type), type);
                Value factor = polynomial(builder, x, y, type, random, Math.max(1, degree - 1));
                yield builder.binary(CoreOps.MUL, factor, zero, type);
            }
            default -> {
                Value sum = builder.binary(CoreOps.ADD, x, y, type);
                Value distributed = builder.binary(CoreOps.MUL, sum, coefficient, type);
                Value left = builder.binary(CoreOps.MUL, x, coefficient, type);
                Value right = builder.binary(CoreOps.MUL, y, coefficient, type);
                Value terms = builder.binary(CoreOps.ADD, left, right, type);
                yield builder.binary(CoreOps.SUB, distributed, terms, type);
            }
        };
    }

    private Value polynomial(IrExpressionBuilder builder, Value x, Value y,
                             PrimitiveType type, SplittableRandom random, int degree) {
        Value left = builder.binary(CoreOps.MUL, builder.constant(randomOdd(random, type), type), x, type);
        Value right = builder.binary(CoreOps.MUL, builder.constant(randomOdd(random, type), type), y, type);
        Value result = builder.binary(CoreOps.ADD,
                builder.binary(CoreOps.ADD, left, right, type),
                builder.constant(randomValue(random, type), type), type);
        for (int level = 1; level < degree; level++) {
            Value maskedX = builder.binary(CoreOps.XOR, x,
                    builder.constant(randomValue(random, type), type), type);
            Value maskedY = builder.binary(CoreOps.OR, y,
                    builder.constant(randomOdd(random, type), type), type);
            Value factor = builder.binary(CoreOps.ADD, maskedX, maskedY, type);
            Value product = builder.binary(CoreOps.MUL, result, factor, type);
            Value affine = builder.binary(CoreOps.ADD,
                    builder.binary(CoreOps.MUL, x, builder.constant(randomOdd(random, type), type), type),
                    builder.constant(randomValue(random, type), type), type);
            result = builder.binary(CoreOps.ADD, product, affine, type);
        }
        return result;
    }

    private Value addIdentity(IrExpressionBuilder builder, Value x, Value y,
                              PrimitiveType type, int variant) {
        if (variant == 0) {
            Value xor = builder.binary(CoreOps.XOR, x, y, type);
            Value and = builder.binary(CoreOps.AND, x, y, type);
            Value two = builder.constant(2, type);
            Value carry = builder.binary(CoreOps.MUL, two, and, type);
            return builder.binary(CoreOps.ADD, xor, carry, type);
        }
        if (variant == 1) {
            Value or = builder.binary(CoreOps.OR, x, y, type);
            Value and = builder.binary(CoreOps.AND, x, y, type);
            return builder.binary(CoreOps.ADD, or, and, type);
        }
        Value or = builder.binary(CoreOps.OR, x, y, type);
        Value two = builder.constant(2, type);
        Value doubled = builder.binary(CoreOps.MUL, two, or, type);
        Value xor = builder.binary(CoreOps.XOR, x, y, type);
        return builder.binary(CoreOps.SUB, doubled, xor, type);
    }

    private Value andIdentity(IrExpressionBuilder builder, Value x, Value y,
                              PrimitiveType type, int variant) {
        if (variant == 0) {
            Value sum = builder.binary(CoreOps.ADD, x, y, type);
            Value or = builder.binary(CoreOps.OR, x, y, type);
            return builder.binary(CoreOps.SUB, sum, or, type);
        }
        if (variant == 1) {
            Value notX = bitwiseNot(builder, x, type);
            Value notY = bitwiseNot(builder, y, type);
            Value or = builder.binary(CoreOps.OR, notX, notY, type);
            return bitwiseNot(builder, or, type);
        }
        Value or = builder.binary(CoreOps.OR, x, y, type);
        Value xor = builder.binary(CoreOps.XOR, x, y, type);
        return builder.binary(CoreOps.SUB, or, xor, type);
    }

    private Value orIdentity(IrExpressionBuilder builder, Value x, Value y,
                             PrimitiveType type, int variant) {
        if (variant == 0) {
            Value sum = builder.binary(CoreOps.ADD, x, y, type);
            Value and = builder.binary(CoreOps.AND, x, y, type);
            return builder.binary(CoreOps.SUB, sum, and, type);
        }
        if (variant == 1) {
            Value notX = bitwiseNot(builder, x, type);
            Value notY = bitwiseNot(builder, y, type);
            Value and = builder.binary(CoreOps.AND, notX, notY, type);
            return bitwiseNot(builder, and, type);
        }
        Value xor = builder.binary(CoreOps.XOR, x, y, type);
        Value and = builder.binary(CoreOps.AND, x, y, type);
        return builder.binary(CoreOps.ADD, xor, and, type);
    }

    private Value xorIdentity(IrExpressionBuilder builder, Value x, Value y,
                              PrimitiveType type, int variant) {
        if (variant == 0) {
            Value or = builder.binary(CoreOps.OR, x, y, type);
            Value and = builder.binary(CoreOps.AND, x, y, type);
            return builder.binary(CoreOps.SUB, or, and, type);
        }
        if (variant == 1) {
            Value sum = builder.binary(CoreOps.ADD, x, y, type);
            Value and = builder.binary(CoreOps.AND, x, y, type);
            Value two = builder.constant(2, type);
            Value carry = builder.binary(CoreOps.MUL, two, and, type);
            return builder.binary(CoreOps.SUB, sum, carry, type);
        }
        Value or = builder.binary(CoreOps.OR, x, y, type);
        Value and = builder.binary(CoreOps.AND, x, y, type);
        Value notAnd = bitwiseNot(builder, and, type);
        return builder.binary(CoreOps.AND, or, notAnd, type);
    }

    private Value negateIdentity(IrExpressionBuilder builder, Value value,
                                 PrimitiveType type, int variant) {
        if (variant == 0) {
            Value not = bitwiseNot(builder, value, type);
            return builder.binary(CoreOps.ADD, not, builder.constant(1, type), type);
        }
        if (variant == 1) {
            return builder.binary(CoreOps.SUB, builder.constant(0, type), value, type);
        }
        Value minusOne = builder.constant(-1, type);
        Value xor = builder.binary(CoreOps.XOR, value, minusOne, type);
        return builder.binary(CoreOps.ADD, xor, builder.constant(1, type), type);
    }

    private Value bitwiseNot(IrExpressionBuilder builder, Value value, PrimitiveType type) {
        return builder.binary(CoreOps.XOR, value, builder.constant(-1, type), type);
    }

    private boolean integerType(Value value) {
        return value.type() instanceof PrimitiveType primitive
                && (primitive.computationalType() == PrimitiveType.INT || primitive == PrimitiveType.LONG);
    }

    private long randomOdd(SplittableRandom random, PrimitiveType type) {
        long value;
        do {
            value = wide(type) ? random.nextLong() | 1L : random.nextInt() | 1;
        } while (value == 1 || value == -1);
        return normalize(value, type);
    }

    private long randomValue(SplittableRandom random, PrimitiveType type) {
        long value;
        do {
            value = wide(type) ? random.nextLong() : random.nextInt();
        } while (value == 0);
        return normalize(value, type);
    }

    private long modularInverse(long odd, PrimitiveType type) {
        long inverse = odd;
        for (int index = 0; index < (wide(type) ? 6 : 5); index++) {
            inverse *= 2L - odd * inverse;
        }
        return normalize(inverse, type);
    }

    private long negate(long value, PrimitiveType type) {
        return normalize(-value, type);
    }

    private long normalize(long value, PrimitiveType type) {
        return wide(type) ? value : (int) value;
    }

    private boolean wide(PrimitiveType type) {
        return type == PrimitiveType.LONG;
    }

    private void copyIdentity(IrInstruction original, Value replacement) {
        if (replacement.definition() instanceof IrInstruction root) {
            original.metadata().copyPersistentTo(root.metadata());
        }
        replacement.setDebugName(original.result().debugName());
    }

    public record Options(int probability, int maximum, int maximumInstructions,
                           int rounds, int polynomialDegree, int zeroTerms,
                           Set<String> operations, boolean conditionals,
                           boolean switchKeys, boolean longComparisons) {
        public Options {
            if (probability < 0 || probability > 100) throw new IllegalArgumentException("probability");
            if (maximum < 0) throw new IllegalArgumentException("maximum");
            if (maximumInstructions < 64) throw new IllegalArgumentException("maximumInstructions");
            if (rounds < 1 || rounds > 3) throw new IllegalArgumentException("rounds");
            if (polynomialDegree < 1 || polynomialDegree > 5) throw new IllegalArgumentException("polynomialDegree");
            if (zeroTerms < 0 || zeroTerms > 4) throw new IllegalArgumentException("zeroTerms");
            operations = operations == null ? Set.of() : operations.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
