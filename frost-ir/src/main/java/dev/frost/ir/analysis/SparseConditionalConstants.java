package dev.frost.ir.analysis;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.SpecialType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Edge-sensitive SCCP lattice over Frost SSA values and executable CFG edges. */
public final class SparseConditionalConstants {
    private final IrMethod method;
    private final Map<Value, ConstantFact> facts = new IdentityHashMap<>();
    private final Set<BasicBlock> executableBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ControlEdge> executableEdges = Collections.newSetFromMap(new IdentityHashMap<>());

    private SparseConditionalConstants(IrMethod method) {
        this.method = method;
        method.parameters().forEach(parameter -> facts.put(parameter.value(), ConstantFact.OVERDEFINED));
        method.blocks().forEach(block -> {
            block.phis().forEach(phi -> facts.put(phi.result(), ConstantFact.UNDEFINED));
            block.instructions().forEach(instruction -> instruction.results()
                    .forEach(value -> facts.put(value, ConstantFact.UNDEFINED)));
            block.outgoingEdges().forEach(edge -> edge.values().forEach(value ->
                    facts.put(value.result(), ConstantFact.OVERDEFINED)));
        });
        method.entryBlock().ifPresent(executableBlocks::add);
        solve();
    }

    public static SparseConditionalConstants compute(IrMethod method) {
        return new SparseConditionalConstants(Objects.requireNonNull(method, "method"));
    }

    public ConstantFact fact(Value value) { method.requireOwned(value); return facts.getOrDefault(value, ConstantFact.OVERDEFINED); }
    public Optional<ConstantFact.Known> constant(Value value) {
        ConstantFact fact = fact(value);
        return fact instanceof ConstantFact.Known known ? Optional.of(known) : Optional.empty();
    }
    public boolean isExecutable(BasicBlock block) { method.requireOwned(block); return executableBlocks.contains(block); }
    public boolean isExecutable(ControlEdge edge) { method.requireOwned(edge); return executableEdges.contains(edge); }
    public Set<BasicBlock> executableBlocks() { return Set.copyOf(executableBlocks); }
    public Set<ControlEdge> executableEdges() { return Set.copyOf(executableEdges); }

    private void solve() {
        boolean changed;
        do {
            changed = false;
            for (BasicBlock block : method.blocks()) {
                if (!executableBlocks.contains(block)) continue;
                for (PhiNode phi : block.phis()) changed |= update(phi.result(), retag(evaluatePhi(phi), phi.result().type()));
                for (IrInstruction instruction : block.instructions()) {
                    if (instruction.results().size() == 1) {
                        changed |= update(instruction.result(), retag(evaluate(instruction), instruction.result().type()));
                    }
                }
                for (ControlEdge exceptional : block.exceptionalSuccessors()) changed |= markEdge(executableBlocks, executableEdges, exceptional);
                changed |= markTerminatorEdges(block);
            }
        } while (changed);
    }

    private ConstantFact evaluatePhi(PhiNode phi) {
        ConstantFact merged = ConstantFact.UNDEFINED;
        boolean sawExecutable = false;
        for (Map.Entry<ControlEdge, Value> input : phi.inputs().entrySet()) {
            if (!executableEdges.contains(input.getKey())) continue;
            sawExecutable = true;
            merged = meet(merged, fact(input.getValue()));
            if (merged instanceof ConstantFact.Overdefined) break;
        }
        return sawExecutable ? merged : ConstantFact.UNDEFINED;
    }

    private ConstantFact evaluate(IrInstruction instruction) {
        if (instruction.operation().code().equals(CoreOps.CONSTANT)) {
            IrAttribute value = instruction.operation().attributes().get("value");
            return value == null ? ConstantFact.OVERDEFINED : new ConstantFact.Known(instruction.result().type(), value);
        }
        if (instruction.operation().code().equals(CoreOps.COPY)) return fact(instruction.operands().getFirst());
        if (instruction.operation().code().equals(CoreOps.SELECT)) {
            Optional<Boolean> condition = truth(fact(instruction.operands().getFirst()));
            return condition.map(value -> fact(instruction.operands().get(value ? 1 : 2))).orElse(ConstantFact.OVERDEFINED);
        }
        if (instruction.operation().code().equals(CoreOps.NEG)) return unaryNumeric(instruction, value -> -value, value -> -value);
        if (instruction.operation().code().equals(CoreOps.CONVERT)) return convert(instruction);
        if (instruction.operation().code().equals(CoreOps.COMPARE)) return compare(instruction);
        if (Set.of(CoreOps.ADD, CoreOps.SUB, CoreOps.MUL, CoreOps.DIV, CoreOps.REM,
                CoreOps.AND, CoreOps.OR, CoreOps.XOR, CoreOps.SHL, CoreOps.SHR, CoreOps.USHR)
                .contains(instruction.operation().code())) return binary(instruction);
        return ConstantFact.OVERDEFINED;
    }

    private ConstantFact unaryNumeric(IrInstruction instruction,
                                      java.util.function.LongUnaryOperator integer,
                                      java.util.function.DoubleUnaryOperator floating) {
        ConstantFact input = fact(instruction.operands().getFirst());
        if (!(input instanceof ConstantFact.Known known)) return input;
        if (known.value() instanceof IrAttribute.LongValue value) {
            return known(instruction.result().type(), integer.applyAsLong(value.value()));
        }
        if (known.value() instanceof IrAttribute.DoubleValue value) {
            return new ConstantFact.Known(instruction.result().type(), IrAttribute.of(floating.applyAsDouble(value.value())));
        }
        return ConstantFact.OVERDEFINED;
    }

    private ConstantFact binary(IrInstruction instruction) {
        ConstantFact leftFact = fact(instruction.operands().get(0));
        ConstantFact rightFact = fact(instruction.operands().get(1));
        if (leftFact instanceof ConstantFact.Undefined || rightFact instanceof ConstantFact.Undefined) return ConstantFact.UNDEFINED;
        if (!(leftFact instanceof ConstantFact.Known left) || !(rightFact instanceof ConstantFact.Known right)) {
            return ConstantFact.OVERDEFINED;
        }
        var operation = instruction.operation().code();
        if (left.value() instanceof IrAttribute.LongValue l && right.value() instanceof IrAttribute.LongValue r) {
            long a = l.value(), b = r.value();
            if (instruction.result().type() instanceof PrimitiveType primitive
                    && primitive.computationalType() == PrimitiveType.INT) {
                int x = (int) a, y = (int) b;
                int value;
                if (operation.equals(CoreOps.ADD)) value = x + y;
                else if (operation.equals(CoreOps.SUB)) value = x - y;
                else if (operation.equals(CoreOps.MUL)) value = x * y;
                else if (operation.equals(CoreOps.DIV)) { if (y == 0) return ConstantFact.OVERDEFINED; value = x / y; }
                else if (operation.equals(CoreOps.REM)) { if (y == 0) return ConstantFact.OVERDEFINED; value = x % y; }
                else if (operation.equals(CoreOps.AND)) value = x & y;
                else if (operation.equals(CoreOps.OR)) value = x | y;
                else if (operation.equals(CoreOps.XOR)) value = x ^ y;
                else if (operation.equals(CoreOps.SHL)) value = x << y;
                else if (operation.equals(CoreOps.SHR)) value = x >> y;
                else if (operation.equals(CoreOps.USHR)) value = x >>> y;
                else return ConstantFact.OVERDEFINED;
                return known(instruction.result().type(), value);
            }
            long value;
            if (operation.equals(CoreOps.ADD)) value = a + b;
            else if (operation.equals(CoreOps.SUB)) value = a - b;
            else if (operation.equals(CoreOps.MUL)) value = a * b;
            else if (operation.equals(CoreOps.DIV)) { if (b == 0) return ConstantFact.OVERDEFINED; value = a / b; }
            else if (operation.equals(CoreOps.REM)) { if (b == 0) return ConstantFact.OVERDEFINED; value = a % b; }
            else if (operation.equals(CoreOps.AND)) value = a & b;
            else if (operation.equals(CoreOps.OR)) value = a | b;
            else if (operation.equals(CoreOps.XOR)) value = a ^ b;
            else if (operation.equals(CoreOps.SHL)) value = a << b;
            else if (operation.equals(CoreOps.SHR)) value = a >> b;
            else if (operation.equals(CoreOps.USHR)) value = a >>> b;
            else return ConstantFact.OVERDEFINED;
            return known(instruction.result().type(), value);
        }
        if (left.value() instanceof IrAttribute.DoubleValue l && right.value() instanceof IrAttribute.DoubleValue r) {
            double a = l.value(), b = r.value();
            boolean single = instruction.result().type() == PrimitiveType.FLOAT;
            if (single) { a = (float) a; b = (float) b; }
            double value;
            if (operation.equals(CoreOps.ADD)) value = single ? (float) a + (float) b : a + b;
            else if (operation.equals(CoreOps.SUB)) value = single ? (float) a - (float) b : a - b;
            else if (operation.equals(CoreOps.MUL)) value = single ? (float) a * (float) b : a * b;
            else if (operation.equals(CoreOps.DIV)) value = single ? (float) a / (float) b : a / b;
            else if (operation.equals(CoreOps.REM)) value = single ? (float) a % (float) b : a % b;
            else return ConstantFact.OVERDEFINED;
            if (single) value = (float) value;
            return new ConstantFact.Known(instruction.result().type(), IrAttribute.of(value));
        }
        return ConstantFact.OVERDEFINED;
    }

    private ConstantFact convert(IrInstruction instruction) {
        ConstantFact input = fact(instruction.operands().getFirst());
        if (!(input instanceof ConstantFact.Known known)) return input;
        IrType target = instruction.result().type();
        if (known.value() instanceof IrAttribute.LongValue value) {
            long number = value.value();
            if (target == PrimitiveType.FLOAT || target == PrimitiveType.DOUBLE) {
                double converted = target == PrimitiveType.FLOAT ? (float) number : (double) number;
                return new ConstantFact.Known(target, IrAttribute.of(converted));
            }
            if (target == PrimitiveType.BYTE) number = (byte) number;
            else if (target == PrimitiveType.CHAR) number = (char) number;
            else if (target == PrimitiveType.SHORT) number = (short) number;
            else if (target == PrimitiveType.INT) number = (int) number;
            return known(target, number);
        }
        if (known.value() instanceof IrAttribute.DoubleValue value) {
            double number = value.value();
            if (target == PrimitiveType.FLOAT || target == PrimitiveType.DOUBLE) {
                return new ConstantFact.Known(target, IrAttribute.of(target == PrimitiveType.FLOAT ? (float) number : number));
            }
            return known(target, target == PrimitiveType.LONG ? (long) number : (int) number);
        }
        return ConstantFact.OVERDEFINED;
    }

    private ConstantFact compare(IrInstruction instruction) {
        ConstantFact left = fact(instruction.operands().get(0));
        ConstantFact right = fact(instruction.operands().get(1));
        if (left instanceof ConstantFact.Undefined || right instanceof ConstantFact.Undefined) return ConstantFact.UNDEFINED;
        if (!(left instanceof ConstantFact.Known l) || !(right instanceof ConstantFact.Known r)) return ConstantFact.OVERDEFINED;
        int result;
        if (l.value() instanceof IrAttribute.LongValue a && r.value() instanceof IrAttribute.LongValue b) {
            result = Long.compare(a.value(), b.value());
        } else if (l.value() instanceof IrAttribute.DoubleValue a && r.value() instanceof IrAttribute.DoubleValue b) {
            String mode = instruction.operation().attributes().get("mode") instanceof IrAttribute.StringValue text ? text.value() : "DCMPL";
            if (Double.isNaN(a.value()) || Double.isNaN(b.value())) result = mode.endsWith("G") ? 1 : -1;
            else result = Double.compare(a.value(), b.value());
        } else return ConstantFact.OVERDEFINED;
        return known(PrimitiveType.INT, result);
    }

    private boolean markTerminatorEdges(BasicBlock block) {
        IrInstruction terminator = block.terminator().orElse(null);
        if (terminator == null) return false;
        if (terminator.operation().code().equals(CoreOps.BRANCH)) {
            return block.normalSuccessors().stream().mapToInt(edge -> markEdge(executableBlocks, executableEdges, edge) ? 1 : 0).sum() > 0;
        }
        if (terminator.operation().code().equals(CoreOps.CONDITIONAL_BRANCH)) {
            Optional<Boolean> outcome = condition(terminator);
            boolean changed = false;
            for (ControlEdge edge : block.normalSuccessors()) {
                if (outcome.isEmpty() || outcome.get() == (edge.kind() == EdgeKind.TRUE)) {
                    changed |= markEdge(executableBlocks, executableEdges, edge);
                }
            }
            return changed;
        }
        if (terminator.operation().code().equals(CoreOps.SWITCH)) {
            ConstantFact key = fact(terminator.operands().getFirst());
            if (key instanceof ConstantFact.Known known && known.value() instanceof IrAttribute.LongValue integer) {
                ControlEdge selected = null;
                for (ControlEdge edge : block.normalSuccessors()) {
                    if (edge.kind() != EdgeKind.SWITCH_CASE) continue;
                    try {
                        if (Long.parseLong(edge.label()) == integer.value()) { selected = edge; break; }
                    } catch (NumberFormatException ignored) {
                        return markAllNormalEdges(block);
                    }
                }
                if (selected == null) selected = block.normalSuccessors().stream()
                        .filter(edge -> edge.kind() == EdgeKind.SWITCH_DEFAULT).findFirst().orElse(null);
                return selected == null ? markAllNormalEdges(block) : markEdge(executableBlocks, executableEdges, selected);
            }
            return markAllNormalEdges(block);
        }
        return false;
    }

    private Optional<Boolean> condition(IrInstruction terminator) {
        ConstantFact left = fact(terminator.operands().get(0));
        ConstantFact right = terminator.operands().size() == 2 ? fact(terminator.operands().get(1)) : null;
        if (!(left instanceof ConstantFact.Known knownLeft)) return Optional.empty();
        String condition = terminator.operation().attributes().get("condition") instanceof IrAttribute.StringValue text
                ? text.value() : "";
        if (condition.equals("IFNULL") || condition.equals("IFNONNULL")) {
            boolean isNull = knownLeft.value() instanceof IrAttribute.StringValue text && text.value().equals("null");
            return Optional.of(condition.equals("IFNULL") == isNull);
        }
        if ((condition.equals("IF_ACMPEQ") || condition.equals("IF_ACMPNE")) && right instanceof ConstantFact.Known knownRight) {
            boolean comparable = knownLeft.value() instanceof IrAttribute.StringValue
                    && knownRight.value() instanceof IrAttribute.StringValue;
            if (comparable) {
                boolean equal = knownLeft.value().equals(knownRight.value());
                return Optional.of(condition.equals("IF_ACMPEQ") == equal);
            }
        }
        if (!(knownLeft.value() instanceof IrAttribute.LongValue leftNumber)) return Optional.empty();
        long a = leftNumber.value();
        long b = 0;
        if (right != null) {
            if (!(right instanceof ConstantFact.Known knownRight) || !(knownRight.value() instanceof IrAttribute.LongValue rightNumber)) {
                return Optional.empty();
            }
            b = rightNumber.value();
        }
        return switch (condition) {
            case "IFEQ", "IF_ICMPEQ", "IF_ACMPEQ" -> Optional.of(a == b);
            case "IFNE", "IF_ICMPNE", "IF_ACMPNE" -> Optional.of(a != b);
            case "IFLT", "IF_ICMPLT" -> Optional.of(a < b);
            case "IFGE", "IF_ICMPGE" -> Optional.of(a >= b);
            case "IFGT", "IF_ICMPGT" -> Optional.of(a > b);
            case "IFLE", "IF_ICMPLE" -> Optional.of(a <= b);
            default -> Optional.empty();
        };
    }

    private Optional<Boolean> truth(ConstantFact fact) {
        if (fact instanceof ConstantFact.Known known && known.value() instanceof IrAttribute.LongValue integer) {
            return Optional.of(integer.value() != 0);
        }
        return Optional.empty();
    }

    private ConstantFact meet(ConstantFact left, ConstantFact right) {
        if (left instanceof ConstantFact.Undefined) return right;
        if (right instanceof ConstantFact.Undefined) return left;
        if (left.equals(right)) return left;
        return ConstantFact.OVERDEFINED;
    }

    private ConstantFact retag(ConstantFact fact, IrType type) {
        return fact instanceof ConstantFact.Known known ? new ConstantFact.Known(type, known.value()) : fact;
    }

    private boolean update(Value value, ConstantFact candidate) {
        ConstantFact previous = facts.getOrDefault(value, ConstantFact.UNDEFINED);
        ConstantFact merged = meet(previous, candidate);
        if (merged.equals(previous)) return false;
        facts.put(value, merged);
        return true;
    }

    private ConstantFact.Known known(IrType type, long value) {
        if (type == PrimitiveType.INT || type == PrimitiveType.BYTE || type == PrimitiveType.CHAR
                || type == PrimitiveType.SHORT || type == PrimitiveType.BOOLEAN) value = (int) value;
        return new ConstantFact.Known(type, IrAttribute.of(value));
    }

    private boolean markEdge(Set<BasicBlock> blocks, Set<ControlEdge> edges, ControlEdge edge) {
        boolean changed = edges.add(edge);
        changed |= blocks.add(edge.target());
        return changed;
    }

    private boolean markAllNormalEdges(BasicBlock block) {
        return block.normalSuccessors().stream()
                .mapToInt(edge -> markEdge(executableBlocks, executableEdges, edge) ? 1 : 0).sum() > 0;
    }
}
