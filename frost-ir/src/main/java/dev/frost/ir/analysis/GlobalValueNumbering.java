package dev.frost.ir.analysis;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeValue;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodParameter;
import dev.frost.ir.model.OperationTrait;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.PrimitiveType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Congruence-based global value numbering. Only speculatable, effect-free operations enter the
 * expression domain; throwing and opaque JVM operations are deliberately unique.
 */
public final class GlobalValueNumbering {
    private final IrMethod method;
    private final List<Value> order;
    private final Map<Value, Integer> initialNumbers = new IdentityHashMap<>();
    private final int[] parent;
    private final Map<Value, SymbolicExpression> expressions = new IdentityHashMap<>();
    private final Map<Integer, Set<Value>> classes;
    private final Map<Integer, Value> leaders;

    private GlobalValueNumbering(IrMethod method) {
        this.method = method;
        order = enumerate(method);
        for (int index = 0; index < order.size(); index++) initialNumbers.put(order.get(index), index);
        parent = new int[order.size()];
        for (int index = 0; index < parent.length; index++) parent[index] = index;
        solve();
        rebuildExpressions();
        classes = buildClasses();
        leaders = buildLeaders();
    }

    public static GlobalValueNumbering compute(IrMethod method) {
        return new GlobalValueNumbering(Objects.requireNonNull(method, "method"));
    }

    public int valueNumber(Value value) {
        method.requireOwned(value);
        Integer number = initialNumbers.get(value);
        if (number == null) throw new IllegalArgumentException("Value is not attached to the method graph");
        return find(number);
    }

    public boolean equivalent(Value left, Value right) {
        return valueNumber(left) == valueNumber(right);
    }

    public Optional<SymbolicExpression> expression(Value value) {
        method.requireOwned(value);
        return Optional.ofNullable(expressions.get(value));
    }

    public Set<Value> equivalentValues(Value value) {
        return classes.getOrDefault(valueNumber(value), Set.of());
    }

    public Value leader(Value value) {
        return leaders.get(valueNumber(value));
    }

    public Map<Integer, Set<Value>> equivalenceClasses() { return classes; }

    private void solve() {
        boolean changed;
        do {
            changed = false;
            Map<SymbolicExpression, Integer> interned = new LinkedHashMap<>();
            for (Value value : order) {
                SymbolicExpression expression = expressionOf(value);
                if (expression instanceof SymbolicExpression.Leaf) continue;
                int number = find(initialNumbers.get(value));
                Integer previous = interned.putIfAbsent(expression, number);
                if (previous != null) changed |= union(previous, number);
            }
        } while (changed);
    }

    private void rebuildExpressions() {
        order.forEach(value -> expressions.put(value, expressionOf(value)));
    }

    private SymbolicExpression expressionOf(Value value) {
        if (value.definition() instanceof MethodParameter parameter) {
            return new SymbolicExpression.Leaf(SymbolicExpression.LeafKind.PARAMETER, parameter.id(), value.type());
        }
        if (value.definition() instanceof PhiNode phi) {
            return new SymbolicExpression.Leaf(SymbolicExpression.LeafKind.PHI, phi.id(), value.type());
        }
        if (value.definition() instanceof EdgeValue edgeValue) {
            return new SymbolicExpression.Leaf(SymbolicExpression.LeafKind.EDGE, edgeValue.id(), value.type());
        }
        if (!(value.definition() instanceof IrInstruction instruction) || !eligible(instruction)) {
            return new SymbolicExpression.Leaf(SymbolicExpression.LeafKind.EFFECTFUL,
                    value.definition().id(), value.type());
        }
        IrAttribute constant = instruction.operation().code().equals(CoreOps.CONSTANT)
                ? instruction.operation().attributes().get("value") : null;
        if (constant != null) return new SymbolicExpression.Constant(value.type(), constant);
        List<Integer> operands = instruction.operands().stream().map(this::valueNumber).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (isSafelyCommutative(instruction) && operands.size() == 2 && operands.get(0) > operands.get(1)) {
            Collections.swap(operands, 0, 1);
        }
        return new SymbolicExpression.Apply(instruction.operation(), value.type(), value.resultIndex(), operands);
    }

    private boolean eligible(IrInstruction instruction) {
        if (instruction.results().size() != 1 || !instruction.effects().isPure()) return false;
        return method.context().schema(instruction.operation().code())
                .map(schema -> schema.hasTrait(OperationTrait.SPECULATABLE)).orElse(false);
    }

    private boolean isSafelyCommutative(IrInstruction instruction) {
        var code = instruction.operation().code();
        if (!(code.equals(CoreOps.ADD) || code.equals(CoreOps.MUL) || code.equals(CoreOps.AND)
                || code.equals(CoreOps.OR) || code.equals(CoreOps.XOR))) return false;
        if (!(instruction.result().type() instanceof PrimitiveType primitive)) return false;
        return primitive != PrimitiveType.FLOAT && primitive != PrimitiveType.DOUBLE && primitive != PrimitiveType.VOID;
    }

    private List<Value> enumerate(IrMethod method) {
        List<Value> values = new ArrayList<>();
        method.parameters().forEach(parameter -> values.add(parameter.value()));
        method.blocks().forEach(block -> {
            block.phis().forEach(phi -> values.add(phi.result()));
            block.instructions().forEach(instruction -> values.addAll(instruction.results()));
            block.outgoingEdges().forEach(edge -> edge.values().forEach(value -> values.add(value.result())));
        });
        return List.copyOf(values);
    }

    private Map<Integer, Set<Value>> buildClasses() {
        Map<Integer, LinkedHashSet<Value>> mutable = new LinkedHashMap<>();
        order.forEach(value -> mutable.computeIfAbsent(valueNumber(value), ignored -> new LinkedHashSet<>()).add(value));
        Map<Integer, Set<Value>> result = new LinkedHashMap<>();
        mutable.forEach((number, values) -> result.put(number,
                Collections.unmodifiableSet(new LinkedHashSet<>(values))));
        return Collections.unmodifiableMap(result);
    }

    private Map<Integer, Value> buildLeaders() {
        Map<Integer, Value> result = new LinkedHashMap<>();
        order.forEach(value -> result.putIfAbsent(valueNumber(value), value));
        return Collections.unmodifiableMap(result);
    }

    private int find(int value) {
        int current = value;
        while (parent[current] != current) current = parent[current];
        while (parent[value] != value) {
            int next = parent[value];
            parent[value] = current;
            value = next;
        }
        return current;
    }

    private boolean union(int left, int right) {
        int a = find(left), b = find(right);
        if (a == b) return false;
        if (a < b) parent[b] = a;
        else parent[a] = b;
        return true;
    }
}
