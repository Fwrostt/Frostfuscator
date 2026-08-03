package dev.frost.obfuscator.transformer.phase5;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.PrimitiveType;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A conservative, side-effect-free int/long SSA expression slice used by Phase 5 transforms. */
public final class PhaseFiveExpression {
    private static final Set<OperationCode> BINARY = Set.of(
            CoreOps.ADD, CoreOps.SUB, CoreOps.MUL, CoreOps.AND, CoreOps.OR, CoreOps.XOR,
            CoreOps.SHL, CoreOps.SHR, CoreOps.USHR
    );

    private PhaseFiveExpression() {}

    public static Optional<Tree> build(Value root, int maximumNodes) {
        if (!supportedType(root) || maximumNodes < 1) return Optional.empty();
        Builder builder = new Builder(maximumNodes);
        Expression expression = builder.expression(root);
        if (expression == null || builder.failed) return Optional.empty();
        return Optional.of(new Tree(root, expression, List.copyOf(builder.captures),
                Set.copyOf(builder.definitions), builder.nodes));
    }

    public static final class Tree {
        private final Value root;
        private final Expression expression;
        private final List<Value> captures;
        private final Set<IrInstruction> definitions;
        private final int size;

        private Tree(Value root, Expression expression, List<Value> captures,
                     Set<IrInstruction> definitions, int size) {
            this.root = root;
            this.expression = expression;
            this.captures = captures;
            this.definitions = definitions;
            this.size = size;
        }

        public Value root() { return root; }
        public PrimitiveType type() { return (PrimitiveType) root.type(); }
        public List<Value> captures() { return captures; }
        public Set<IrInstruction> definitions() { return definitions; }
        public int size() { return size; }

        public int captureSlots() {
            return captures.stream().mapToInt(value -> value.type().slots()).sum();
        }

        public String descriptor() {
            StringBuilder descriptor = new StringBuilder("(");
            for (Value capture : captures) descriptor.append(capture.type().displayName());
            return descriptor.append(')').append(root.type().displayName()).toString();
        }

        public void emit(InsnList output) {
            emit(output, 0);
        }

        public void emit(InsnList output, int firstLocal) {
            Map<Value, Integer> locals = new LinkedHashMap<>();
            int local = firstLocal;
            for (Value capture : captures) {
                locals.put(capture, local);
                local += capture.type().slots();
            }
            expression.emit(output, locals);
        }

        /** Removes only now-dead pure definitions; shared definitions remain in the caller. */
        public int eraseDeadDefinitions() {
            List<IrInstruction> reverse = new ArrayList<>(definitions);
            Collections.reverse(reverse);
            int erased = 0;
            boolean progress;
            do {
                progress = false;
                for (IrInstruction instruction : reverse) {
                    if (instruction.block().isEmpty()
                            || instruction.results().stream().anyMatch(Value::isUsed)) continue;
                    instruction.erase();
                    erased++;
                    progress = true;
                }
            } while (progress);
            return erased;
        }
    }

    private interface Expression {
        int size();
        void emit(InsnList output, Map<Value, Integer> captureLocals);
    }

    private record Capture(Value value) implements Expression {
        @Override public int size() { return 1; }
        @Override public void emit(InsnList output, Map<Value, Integer> captureLocals) {
            PrimitiveType type = (PrimitiveType) value.type();
            output.add(new VarInsnNode(type == PrimitiveType.LONG ? Opcodes.LLOAD : Opcodes.ILOAD,
                    captureLocals.get(value)));
        }
    }

    private record Constant(long value, PrimitiveType type) implements Expression {
        @Override public int size() { return 1; }
        @Override public void emit(InsnList output, Map<Value, Integer> captureLocals) {
            if (type == PrimitiveType.LONG) pushLong(output, value);
            else pushInteger(output, (int) value);
        }
    }

    private record Unary(int opcode, Expression operand) implements Expression {
        @Override public int size() { return operand.size() + 1; }
        @Override public void emit(InsnList output, Map<Value, Integer> captureLocals) {
            operand.emit(output, captureLocals);
            output.add(new InsnNode(opcode));
        }
    }

    private record Binary(int opcode, Expression left, Expression right) implements Expression {
        @Override public int size() { return left.size() + right.size() + 1; }
        @Override public void emit(InsnList output, Map<Value, Integer> captureLocals) {
            left.emit(output, captureLocals);
            right.emit(output, captureLocals);
            output.add(new InsnNode(opcode));
        }
    }

    private static final class Builder {
        private final int maximumNodes;
        private final LinkedHashSet<Value> captures = new LinkedHashSet<>();
        private final Set<IrInstruction> definitions = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Value> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private int nodes;
        private boolean failed;

        private Builder(int maximumNodes) { this.maximumNodes = maximumNodes; }

        private Expression expression(Value value) {
            if (!supportedType(value)) return fail();
            if (!active.add(value)) return fail();
            try {
                if (++nodes > maximumNodes) return fail();
                if (!(value.definition() instanceof IrInstruction instruction)
                        || instruction.results().size() != 1
                        || !instruction.effects().isPure()) {
                    captures.add(value);
                    return new Capture(value);
                }

                OperationCode code = instruction.operation().code();
                Expression built;
                if (code.equals(CoreOps.CONSTANT)) {
                    Long constant = constant(instruction);
                    if (constant == null) return capture(value);
                    built = new Constant(constant, (PrimitiveType) value.type());
                } else if (code.equals(CoreOps.COPY) && instruction.operands().size() == 1) {
                    built = expression(instruction.operands().getFirst());
                } else if (code.equals(CoreOps.NEG) && instruction.operands().size() == 1) {
                    Expression operand = expression(instruction.operands().getFirst());
                    if (operand == null) return null;
                    built = new Unary(value.type() == PrimitiveType.LONG ? Opcodes.LNEG : Opcodes.INEG, operand);
                } else if (BINARY.contains(code) && instruction.operands().size() == 2) {
                    Expression left = expression(instruction.operands().get(0));
                    Expression right = expression(instruction.operands().get(1));
                    if (left == null || right == null) return null;
                    built = new Binary(opcode(code, (PrimitiveType) value.type()), left, right);
                } else {
                    return capture(value);
                }
                definitions.add(instruction);
                return built;
            } finally {
                active.remove(value);
            }
        }

        private Expression capture(Value value) {
            captures.add(value);
            return new Capture(value);
        }

        private Expression fail() {
            failed = true;
            return null;
        }
    }

    private static boolean supportedType(Value value) {
        return value.type() == PrimitiveType.INT || value.type() == PrimitiveType.LONG;
    }

    private static Long constant(IrInstruction instruction) {
        IrAttribute attribute = instruction.operation().attributes().get("value");
        return attribute instanceof IrAttribute.LongValue number ? number.value() : null;
    }

    private static int opcode(OperationCode code, PrimitiveType resultType) {
        boolean wide = resultType == PrimitiveType.LONG;
        if (code.equals(CoreOps.ADD)) return wide ? Opcodes.LADD : Opcodes.IADD;
        if (code.equals(CoreOps.SUB)) return wide ? Opcodes.LSUB : Opcodes.ISUB;
        if (code.equals(CoreOps.MUL)) return wide ? Opcodes.LMUL : Opcodes.IMUL;
        if (code.equals(CoreOps.AND)) return wide ? Opcodes.LAND : Opcodes.IAND;
        if (code.equals(CoreOps.OR)) return wide ? Opcodes.LOR : Opcodes.IOR;
        if (code.equals(CoreOps.XOR)) return wide ? Opcodes.LXOR : Opcodes.IXOR;
        if (code.equals(CoreOps.SHL)) return wide ? Opcodes.LSHL : Opcodes.ISHL;
        if (code.equals(CoreOps.SHR)) return wide ? Opcodes.LSHR : Opcodes.ISHR;
        if (code.equals(CoreOps.USHR)) return wide ? Opcodes.LUSHR : Opcodes.IUSHR;
        throw new IllegalArgumentException("Unsupported expression operation " + code.qualifiedName());
    }

    private static void pushInteger(InsnList output, int value) {
        if (value >= -1 && value <= 5) output.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) output.add(new IntInsnNode(Opcodes.BIPUSH, value));
        else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) output.add(new IntInsnNode(Opcodes.SIPUSH, value));
        else output.add(new LdcInsnNode(value));
    }

    private static void pushLong(InsnList output, long value) {
        if (value == 0L) output.add(new InsnNode(Opcodes.LCONST_0));
        else if (value == 1L) output.add(new InsnNode(Opcodes.LCONST_1));
        else output.add(new LdcInsnNode(value));
    }
}
