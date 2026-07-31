package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.ASMHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Generates verifier-safe polymorphic mixed Boolean-arithmetic expressions over
 * the JVM's 32-bit and 64-bit modular integer rings.
 */
public final class MixedBooleanArithmeticTransformer extends Transformer {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_OUTPUT_INSTRUCTIONS = 20_000;
    private static final int MAX_ESTIMATED_BYTECODE = 56_000;

    @Override
    public String getName() {
        return "mixed-boolean-arithmetic";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        Counts counts = apply(context.pool(), context.config());
        context.stats().add("mbaOperations", counts.arithmetic.sum());
        context.stats().add("mbaConditionals", counts.conditionals.sum());
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        apply(pool, config);
    }

    private Counts apply(ClassPool pool, TransformerConfig config) {
        int probability = intOption(config, "probability", 70, 0, 100);
        int rounds = intOption(config, "rounds", 1, 1, 3);
        int polynomialDegree = intOption(config, "polynomial-degree", 3, 1, 5);
        int zeroTerms = intOption(config, "zero-terms", 2, 0, 4);
        int maximumPerMethod = intOption(config, "max-per-method", 64, 0, 512);
        int maximumPerClass = intOption(config, "max-per-class", 256, 0, 4_096);
        int maximumMethodInstructions = intOption(config, "max-method-instructions", 6_000, 64,
                MAX_OUTPUT_INSTRUCTIONS);
        int maximumOutputInstructions = intOption(config, "max-output-method-instructions", 12_000,
                maximumMethodInstructions, MAX_OUTPUT_INSTRUCTIONS);
        boolean conditionals = booleanOption(config, "conditionals", true);
        boolean switchKeys = booleanOption(config, "switch-keys", true);
        boolean longComparisons = booleanOption(config, "long-comparisons", true);
        boolean includeSynthetic = booleanOption(config, "include-synthetic", false);
        Set<String> operations = operations(config);
        long configuredSeed = longOption(config, "seed", 0L);
        long runSeed = configuredSeed == 0L ? SECURE_RANDOM.nextLong() : configuredSeed;
        Counts counts = new Counts();

        pool.forEachClass(owner -> {
            if (!shouldProcess(owner.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) return;
            Random random = new Random(runSeed ^ owner.name.hashCode());
            int changedInClass = 0;
            for (MethodNode method : owner.methods) {
                if (!eligible(method, maximumMethodInstructions, includeSynthetic)
                        || changedInClass >= maximumPerClass) continue;

                LocalState locals = new LocalState(ASMHelper.nextFreeLocal(method));
                List<Candidate> candidates = candidates(method, operations, conditionals, switchKeys, longComparisons);
                int changedInMethod = 0;
                int estimatedBytecode = estimatedBytecodeSize(method.instructions);
                for (Candidate candidate : candidates) {
                    if (changedInMethod >= maximumPerMethod || changedInClass >= maximumPerClass
                            || method.instructions.size() >= maximumOutputInstructions) break;
                    if (random.nextInt(100) >= probability) continue;

                    InsnList replacement = rewrite(candidate, locals, random,
                            rounds, polynomialDegree, zeroTerms);
                    if (replacement == null) continue;
                    int removed = candidate.kind == CandidateKind.ARITHMETIC ? 1 : 0;
                    int replacementBytes = estimatedBytecodeSize(replacement);
                    int removedBytes = removed == 1 ? estimatedInstructionSize(candidate.instruction) : 0;
                    if (method.instructions.size() + replacement.size() - removed > maximumOutputInstructions) {
                        continue;
                    }
                    if (estimatedBytecode + replacementBytes - removedBytes > MAX_ESTIMATED_BYTECODE) continue;
                    method.instructions.insertBefore(candidate.instruction, replacement);
                    if (removed == 1) method.instructions.remove(candidate.instruction);
                    estimatedBytecode += replacementBytes - removedBytes;
                    method.maxLocals = Math.max(method.maxLocals, locals.next);
                    changedInMethod++;
                    changedInClass++;
                    if (candidate.kind == CandidateKind.ARITHMETIC) counts.arithmetic.increment();
                    else counts.conditionals.increment();
                }
            }
            if (changedInClass > 0) {
                pool.markFramesDirty(owner.name);
                detail("Generated {} polymorphic MBA expressions in {}", changedInClass, owner.name);
            }
        });
        return counts;
    }

    private static boolean eligible(MethodNode method, int maximumInstructions, boolean includeSynthetic) {
        return method.instructions != null && method.instructions.size() > 0
                && method.instructions.size() <= maximumInstructions
                && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                && (includeSynthetic || (method.access & Opcodes.ACC_SYNTHETIC) == 0);
    }

    private List<Candidate> candidates(MethodNode method, Set<String> operations,
                                       boolean conditionals, boolean switchKeys, boolean longComparisons) {
        List<Candidate> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (operationEnabled(opcode, operations)) {
                result.add(new Candidate(instruction, CandidateKind.ARITHMETIC));
            } else if (conditionals && instruction instanceof JumpInsnNode && isUnaryIntegerJump(opcode)) {
                result.add(new Candidate(instruction, CandidateKind.UNARY_CONDITION));
            } else if (conditionals && instruction instanceof JumpInsnNode && isBinaryIntegerJump(opcode)) {
                result.add(new Candidate(instruction, CandidateKind.BINARY_CONDITION));
            } else if (switchKeys && (instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode)) {
                result.add(new Candidate(instruction, CandidateKind.SWITCH_KEY));
            } else if (longComparisons && opcode == Opcodes.LCMP) {
                result.add(new Candidate(instruction, CandidateKind.LONG_COMPARISON));
            }
        }
        return result;
    }

    private InsnList rewrite(Candidate candidate, LocalState state, Random random,
                             int rounds, int degree, int zeroTerms) {
        return switch (candidate.kind) {
            case ARITHMETIC -> rewriteArithmetic(candidate.instruction.getOpcode(), state,
                    random, rounds, degree, zeroTerms);
            case UNARY_CONDITION, SWITCH_KEY -> rewriteUnaryIdentity(state, false,
                    random, rounds, degree, zeroTerms);
            case BINARY_CONDITION -> rewriteBinaryIdentity(state, false,
                    random, rounds, degree, zeroTerms);
            case LONG_COMPARISON -> rewriteBinaryIdentity(state, true,
                    random, rounds, degree, zeroTerms);
        };
    }

    private InsnList rewriteArithmetic(int opcode, LocalState state, Random random,
                                       int rounds, int degree, int zeroTerms) {
        boolean wide = isLongArithmetic(opcode);
        LocalPair pair = state.pair(wide);
        InsnList list = new InsnList();
        Expression x = new Load(pair.left, wide);
        if (opcode == Opcodes.INEG || opcode == Opcodes.LNEG) {
            list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, pair.left));
            Expression y = xor(x, constant(randomValue(random, wide), wide));
            Expression base = negateIdentity(x, random.nextInt(3));
            decorate(base, x, y, wide, random, rounds, degree, zeroTerms).emit(list);
            return list;
        }

        list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, pair.right));
        list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, pair.left));
        Expression y = new Load(pair.right, wide);
        Expression base = switch (opcode) {
            case Opcodes.IADD, Opcodes.LADD -> addIdentity(x, y, random.nextInt(3));
            case Opcodes.ISUB, Opcodes.LSUB -> addIdentity(x, negateIdentity(y, random.nextInt(3)),
                    random.nextInt(3));
            case Opcodes.IMUL, Opcodes.LMUL -> multiply(x, y);
            case Opcodes.IAND, Opcodes.LAND -> andIdentity(x, y, random.nextInt(3));
            case Opcodes.IOR, Opcodes.LOR -> orIdentity(x, y, random.nextInt(3));
            case Opcodes.IXOR, Opcodes.LXOR -> xorIdentity(x, y, random.nextInt(3));
            default -> null;
        };
        if (base == null) return null;
        decorate(base, x, y, wide, random, rounds, degree, zeroTerms).emit(list);
        return list;
    }

    private InsnList rewriteUnaryIdentity(LocalState state, boolean wide, Random random,
                                          int rounds, int degree, int zeroTerms) {
        LocalPair pair = state.pair(wide);
        InsnList list = new InsnList();
        list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, pair.left));
        Expression x = new Load(pair.left, wide);
        Expression y = xor(x, constant(randomValue(random, wide), wide));
        decorate(x, x, y, wide, random, rounds, degree, zeroTerms).emit(list);
        return list;
    }

    private InsnList rewriteBinaryIdentity(LocalState state, boolean wide, Random random,
                                           int rounds, int degree, int zeroTerms) {
        LocalPair pair = state.pair(wide);
        InsnList list = new InsnList();
        list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, pair.right));
        list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, pair.left));
        Expression x = new Load(pair.left, wide);
        Expression y = new Load(pair.right, wide);
        decorate(x, x, y, wide, random, rounds, degree, zeroTerms).emit(list);
        decorate(y, y, x, wide, random, rounds, degree, zeroTerms).emit(list);
        return list;
    }

    private Expression decorate(Expression value, Expression x, Expression y, boolean wide,
                                Random random, int rounds, int degree, int zeroTerms) {
        Expression result = value;
        for (int round = 0; round < rounds; round++) {
            long multiplier = randomOdd(random, wide);
            long inverse = modularInverse(multiplier, wide);
            long mask = randomValue(random, wide);
            Expression masked = add(multiply(constant(multiplier, wide), result), constant(mask, wide));
            result = multiply(constant(inverse, wide),
                    add(masked, constant(negateValue(mask, wide), wide)));
            for (int term = 0; term < zeroTerms; term++) {
                result = add(result, zeroPolynomial(x, y, wide, random, degree, term));
            }
        }
        return result;
    }

    private Expression zeroPolynomial(Expression x, Expression y, boolean wide,
                                      Random random, int degree, int salt) {
        Expression coefficient = polynomial(x, y, wide, random, degree);
        return switch (Math.floorMod(random.nextInt() + salt, 4)) {
            case 0 -> multiply(coefficient, subtract(addIdentity(x, y, random.nextInt(3)), add(x, y)));
            case 1 -> {
                Expression u = add(coefficient, xor(x, y));
                yield multiply(polynomial(y, x, wide, random, degree),
                        add(or(u, not(u, wide)), constant(1, wide)));
            }
            case 2 -> {
                Expression u = add(coefficient, and(x, not(y, wide)));
                yield multiply(polynomial(x, y, wide, random, Math.max(1, degree - 1)),
                        and(multiply(u, add(u, constant(1, wide))), constant(1, wide)));
            }
            default -> subtract(multiply(add(x, y), coefficient),
                    add(multiply(x, coefficient), multiply(y, coefficient)));
        };
    }

    private Expression polynomial(Expression x, Expression y, boolean wide, Random random, int degree) {
        Expression result = add(add(multiply(constant(randomOdd(random, wide), wide), x),
                        multiply(constant(randomOdd(random, wide), wide), y)),
                constant(randomValue(random, wide), wide));
        for (int level = 1; level < degree; level++) {
            Expression factor = add(xor(x, constant(randomValue(random, wide), wide)),
                    or(y, constant(randomOdd(random, wide), wide)));
            result = add(multiply(result, factor),
                    add(multiply(x, constant(randomOdd(random, wide), wide)),
                            constant(randomValue(random, wide), wide)));
        }
        return result;
    }

    private Expression addIdentity(Expression x, Expression y, int variant) {
        return switch (variant) {
            case 0 -> add(xor(x, y), multiply(constant(2, x.wide()), and(x, y)));
            case 1 -> add(or(x, y), and(x, y));
            default -> subtract(multiply(constant(2, x.wide()), or(x, y)), xor(x, y));
        };
    }

    private Expression andIdentity(Expression x, Expression y, int variant) {
        return switch (variant) {
            case 0 -> subtract(add(x, y), or(x, y));
            case 1 -> not(or(not(x, x.wide()), not(y, y.wide())), x.wide());
            default -> subtract(or(x, y), xor(x, y));
        };
    }

    private Expression orIdentity(Expression x, Expression y, int variant) {
        return switch (variant) {
            case 0 -> subtract(add(x, y), and(x, y));
            case 1 -> not(and(not(x, x.wide()), not(y, y.wide())), x.wide());
            default -> add(xor(x, y), and(x, y));
        };
    }

    private Expression xorIdentity(Expression x, Expression y, int variant) {
        return switch (variant) {
            case 0 -> subtract(or(x, y), and(x, y));
            case 1 -> subtract(add(x, y), multiply(constant(2, x.wide()), and(x, y)));
            default -> and(or(x, y), not(and(x, y), x.wide()));
        };
    }

    private Expression negateIdentity(Expression value, int variant) {
        return switch (variant) {
            case 0 -> add(not(value, value.wide()), constant(1, value.wide()));
            case 1 -> subtract(constant(0, value.wide()), value);
            default -> add(xor(value, constant(-1, value.wide())), constant(1, value.wide()));
        };
    }

    private boolean operationEnabled(int opcode, Set<String> operations) {
        return switch (opcode) {
            case Opcodes.IADD, Opcodes.LADD -> operations.contains("add");
            case Opcodes.ISUB, Opcodes.LSUB -> operations.contains("sub");
            case Opcodes.IMUL, Opcodes.LMUL -> operations.contains("mul");
            case Opcodes.IAND, Opcodes.LAND -> operations.contains("and");
            case Opcodes.IOR, Opcodes.LOR -> operations.contains("or");
            case Opcodes.IXOR, Opcodes.LXOR -> operations.contains("xor");
            case Opcodes.INEG, Opcodes.LNEG -> operations.contains("neg");
            default -> false;
        };
    }

    private static boolean isUnaryIntegerJump(int opcode) {
        return opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE;
    }

    private static boolean isBinaryIntegerJump(int opcode) {
        return opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE;
    }

    private static boolean isLongArithmetic(int opcode) {
        return opcode == Opcodes.LADD || opcode == Opcodes.LSUB || opcode == Opcodes.LMUL
                || opcode == Opcodes.LAND || opcode == Opcodes.LOR || opcode == Opcodes.LXOR
                || opcode == Opcodes.LNEG;
    }

    private Set<String> operations(TransformerConfig config) {
        String configured = config.getOption("operations", "add,sub,mul,and,or,xor,neg");
        Set<String> result = new HashSet<>();
        for (String value : configured.split("[,;\\s]+")) {
            if (!value.isBlank()) result.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static Expression constant(long value, boolean wide) {
        return new Constant(value, wide);
    }

    private static Expression add(Expression left, Expression right) {
        return new Binary(ExpressionOp.ADD, left, right);
    }

    private static Expression subtract(Expression left, Expression right) {
        return new Binary(ExpressionOp.SUBTRACT, left, right);
    }

    private static Expression multiply(Expression left, Expression right) {
        return new Binary(ExpressionOp.MULTIPLY, left, right);
    }

    private static Expression and(Expression left, Expression right) {
        return new Binary(ExpressionOp.AND, left, right);
    }

    private static Expression or(Expression left, Expression right) {
        return new Binary(ExpressionOp.OR, left, right);
    }

    private static Expression xor(Expression left, Expression right) {
        return new Binary(ExpressionOp.XOR, left, right);
    }

    private static Expression not(Expression value, boolean wide) {
        return xor(value, constant(-1, wide));
    }

    private static long randomOdd(Random random, boolean wide) {
        long value;
        do {
            value = wide ? random.nextLong() | 1L : random.nextInt() | 1;
        } while (value == 1L || value == -1L);
        return wide ? value : (int) value;
    }

    private static long randomValue(Random random, boolean wide) {
        long value;
        do {
            value = wide ? random.nextLong() : random.nextInt();
        } while (value == 0L);
        return wide ? value : (int) value;
    }

    private static long modularInverse(long odd, boolean wide) {
        long inverse = odd;
        int iterations = wide ? 6 : 5;
        for (int index = 0; index < iterations; index++) inverse *= 2L - odd * inverse;
        return wide ? inverse : (int) inverse;
    }

    private static long negateValue(long value, boolean wide) {
        return wide ? -value : (int) -(int) value;
    }

    /**
     * Conservatively estimates Code attribute growth. The instruction-count cap
     * controls decompiler expansion while this second guard leaves headroom for
     * constant-pool indexes, switch padding, and branch widening at write time.
     */
    private static int estimatedBytecodeSize(InsnList instructions) {
        int size = 0;
        for (AbstractInsnNode instruction : instructions) size += estimatedInstructionSize(instruction);
        return size;
    }

    private static int estimatedInstructionSize(AbstractInsnNode instruction) {
        if (instruction.getOpcode() < 0) return 0;
        if (instruction instanceof LookupSwitchInsnNode lookup) return 12 + lookup.keys.size() * 8;
        if (instruction instanceof TableSwitchInsnNode table) return 16 + table.labels.size() * 4;
        if (instruction instanceof IincInsnNode increment) return increment.var > 255 ? 6 : 3;
        if (instruction instanceof VarInsnNode variable) return variable.var > 255 ? 4 : 2;
        if (instruction instanceof MethodInsnNode method && method.getOpcode() == Opcodes.INVOKEINTERFACE) return 5;
        if (instruction instanceof InvokeDynamicInsnNode) return 5;
        if (instruction instanceof MultiANewArrayInsnNode) return 4;
        if (instruction instanceof LdcInsnNode) return 3;
        if (instruction instanceof FieldInsnNode || instruction instanceof MethodInsnNode
                || instruction instanceof TypeInsnNode || instruction instanceof JumpInsnNode) return 3;
        if (instruction instanceof IntInsnNode integer) return integer.getOpcode() == Opcodes.SIPUSH ? 3 : 2;
        return 1;
    }

    private int intOption(TransformerConfig config, String key, int fallback, int minimum, int maximum) {
        Object value = config.getOptions().get(key);
        int parsed = fallback;
        if (value instanceof Number number) parsed = number.intValue();
        else if (value != null) {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private long longOption(TransformerConfig config, String key, long fallback) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number number) return number.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanOption(TransformerConfig config, String key, boolean fallback) {
        Object value = config.getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private enum CandidateKind { ARITHMETIC, UNARY_CONDITION, BINARY_CONDITION, SWITCH_KEY, LONG_COMPARISON }

    private record Candidate(AbstractInsnNode instruction, CandidateKind kind) {
    }

    private static final class LocalState {
        private int next;
        private LocalPair integers;
        private LocalPair longs;

        private LocalState(int next) {
            this.next = next;
        }

        private LocalPair pair(boolean wide) {
            if (wide) {
                if (longs == null) {
                    longs = new LocalPair(next, next + 2);
                    next += 4;
                }
                return longs;
            }
            if (integers == null) {
                integers = new LocalPair(next, next + 1);
                next += 2;
            }
            return integers;
        }
    }

    private record LocalPair(int left, int right) {
    }

    private enum ExpressionOp {
        ADD(Opcodes.IADD, Opcodes.LADD),
        SUBTRACT(Opcodes.ISUB, Opcodes.LSUB),
        MULTIPLY(Opcodes.IMUL, Opcodes.LMUL),
        AND(Opcodes.IAND, Opcodes.LAND),
        OR(Opcodes.IOR, Opcodes.LOR),
        XOR(Opcodes.IXOR, Opcodes.LXOR);

        private final int integerOpcode;
        private final int longOpcode;

        ExpressionOp(int integerOpcode, int longOpcode) {
            this.integerOpcode = integerOpcode;
            this.longOpcode = longOpcode;
        }
    }

    private sealed interface Expression permits Load, Constant, Binary {
        boolean wide();
        void emit(InsnList list);
    }

    private record Load(int local, boolean wide) implements Expression {
        @Override
        public void emit(InsnList list) {
            list.add(new VarInsnNode(wide ? Opcodes.LLOAD : Opcodes.ILOAD, local));
        }
    }

    private record Constant(long value, boolean wide) implements Expression {
        @Override
        public void emit(InsnList list) {
            pushConstant(list, value, wide);
        }
    }

    private record Binary(ExpressionOp operation, Expression left, Expression right) implements Expression {
        private Binary {
            if (left.wide() != right.wide()) throw new IllegalArgumentException("Mixed MBA widths");
        }

        @Override
        public boolean wide() {
            return left.wide();
        }

        @Override
        public void emit(InsnList list) {
            left.emit(list);
            right.emit(list);
            list.add(new InsnNode(wide() ? operation.longOpcode : operation.integerOpcode));
        }
    }

    private static void pushConstant(InsnList list, long value, boolean wide) {
        if (wide) {
            if (value == 0L) list.add(new InsnNode(Opcodes.LCONST_0));
            else if (value == 1L) list.add(new InsnNode(Opcodes.LCONST_1));
            else list.add(new LdcInsnNode(value));
            return;
        }
        int integer = (int) value;
        if (integer >= -1 && integer <= 5) list.add(new InsnNode(Opcodes.ICONST_0 + integer));
        else if (integer >= Byte.MIN_VALUE && integer <= Byte.MAX_VALUE) {
            list.add(new IntInsnNode(Opcodes.BIPUSH, integer));
        } else if (integer >= Short.MIN_VALUE && integer <= Short.MAX_VALUE) {
            list.add(new IntInsnNode(Opcodes.SIPUSH, integer));
        } else list.add(new LdcInsnNode(integer));
    }

    private static final class Counts {
        private final LongAdder arithmetic = new LongAdder();
        private final LongAdder conditionals = new LongAdder();
    }
}
