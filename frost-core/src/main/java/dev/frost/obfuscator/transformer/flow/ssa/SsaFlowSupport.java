package dev.frost.obfuscator.transformer.flow.ssa;

import dev.frost.ir.analysis.DominatorTree;
import dev.frost.ir.analysis.EdgePolicy;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.EdgeValue;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodParameter;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.ReferenceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Shared ownership-safe graph surgery used only by the Phase 3/4 flow passes. */
final class SsaFlowSupport {
    private SsaFlowSupport() {}

    static List<Value> availableIntValues(IrMethod method, BasicBlock block, IrInstruction anchor,
                                          Predicate<Value> runtimeValue) {
        DominatorTree dominators = DominatorTree.compute(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        LinkedHashSet<Value> values = new LinkedHashSet<>();
        anchor.operands().stream().filter(SsaFlowSupport::isRealInt).filter(runtimeValue).forEach(values::add);

        int anchorIndex = block.instructions().indexOf(anchor);
        for (int index = anchorIndex - 1; index >= 0; index--) {
            IrInstruction instruction = block.instructions().get(index);
            if (!instruction.operation().code().equals(CoreOps.CONSTANT)) {
                instruction.results().stream().filter(SsaFlowSupport::isRealInt).filter(runtimeValue).forEach(values::add);
            }
        }
        block.phis().stream().map(PhiNode::result).filter(SsaFlowSupport::isRealInt).filter(runtimeValue).forEach(values::add);
        method.parameters().stream().map(MethodParameter::value).filter(SsaFlowSupport::isRealInt).filter(runtimeValue)
                .forEach(values::add);

        for (BasicBlock definitionBlock : method.blocks()) {
            if (definitionBlock == block || !dominators.dominates(definitionBlock, block)) continue;
            for (PhiNode phi : definitionBlock.phis()) {
                if (isRealInt(phi.result()) && runtimeValue.test(phi.result())) values.add(phi.result());
            }
            for (IrInstruction instruction : definitionBlock.instructions()) {
                if (instruction.operation().code().equals(CoreOps.CONSTANT)) continue;
                instruction.results().stream().filter(SsaFlowSupport::isRealInt).filter(runtimeValue).forEach(values::add);
            }
        }
        return List.copyOf(values);
    }

    static boolean isRealInt(Value value) {
        return value.type() instanceof PrimitiveType primitive
                && primitive.computationalType() == PrimitiveType.INT
                && !(value.definition() instanceof EdgeValue)
                && (!(value.definition() instanceof IrInstruction instruction)
                || !instruction.operation().code().equals(CoreOps.CONSTANT));
    }

    /** Inserts an algebraic invariant that is zero for every int-family input, including overflow. */
    static Value insertEvenProductPredicate(IrMethod method, BasicBlock block, IrInstruction anchor,
                                            Value source, int key, String family) {
        int index = block.instructions().indexOf(anchor);
        if (index < 0) throw new IllegalArgumentException("Predicate anchor is not in its block");
        String normalized = family == null ? "arithmetic" : family.toLowerCase();
        if (normalized.equals("bitwise")) {
            IrInstruction zero = instruction(method, CoreOps.XOR, List.of(source, source), PrimitiveType.INT, Map.of());
            block.insert(index, zero);
            return zero.result();
        }
        if (normalized.equals("reversible")) {
            IrInstruction constant = constant(method, key == 0 ? 0x6d2b79f5 : key, PrimitiveType.INT);
            IrInstruction encoded = instruction(method, CoreOps.XOR, List.of(source, constant.result()),
                    PrimitiveType.INT, Map.of());
            IrInstruction decoded = instruction(method, CoreOps.XOR, List.of(encoded.result(), constant.result()),
                    PrimitiveType.INT, Map.of());
            IrInstruction zero = instruction(method, CoreOps.XOR, List.of(decoded.result(), source),
                    PrimitiveType.INT, Map.of());
            block.insert(index++, constant);
            block.insert(index++, encoded);
            block.insert(index++, decoded);
            block.insert(index, zero);
            return zero.result();
        }

        IrInstruction one = constant(method, 1, PrimitiveType.INT);
        IrInstruction next = instruction(method, CoreOps.ADD, List.of(source, one.result()),
                PrimitiveType.INT, Map.of());
        IrInstruction product = instruction(method, CoreOps.MUL, List.of(source, next.result()),
                PrimitiveType.INT, Map.of());
        IrInstruction mask = constant(method, 1, PrimitiveType.INT);
        IrInstruction zero = instruction(method, CoreOps.AND, List.of(product.result(), mask.result()),
                PrimitiveType.INT, Map.of());
        block.insert(index++, one);
        block.insert(index++, next);
        block.insert(index++, product);
        block.insert(index++, mask);
        block.insert(index, zero);
        return zero.result();
    }

    static GuardRewrite guardTerminator(IrMethod method, BasicBlock block, Value zeroPredicate,
                                        String namePrefix, boolean terminateFalsePath) {
        IrInstruction terminator = block.terminator()
                .orElseThrow(() -> new IllegalArgumentException("Guard source has no terminator"));
        List<EdgeSnapshot> normalEdges = snapshots(block.normalSuccessors());
        if (normalEdges.stream().anyMatch(snapshot -> !snapshot.edge().values().isEmpty())) {
            throw new IllegalStateException("Normal edge values are not supported by the flow guard rewrite");
        }

        BasicBlock continuation = method.createBlock(uniqueBlockName(method, namePrefix + "$continue"));
        BasicBlock falsePath = method.createBlock(uniqueBlockName(method, namePrefix + "$false"));
        IrInstruction clonedTerminator = method.createInstruction(terminator.operation(), terminator.operands(), List.of());
        terminator.metadata().copyPersistentTo(clonedTerminator.metadata());
        continuation.append(clonedTerminator);

        for (EdgeSnapshot snapshot : normalEdges) {
            ControlEdge replacement = method.connect(continuation, snapshot.edge().target(), snapshot.edge().kind(),
                    snapshot.edge().label(), snapshot.edge().catchType().orElse(null), snapshot.edge().priority());
            snapshot.edge().metadata().copyPersistentTo(replacement.metadata());
            snapshot.phiInputs().forEach((phi, value) -> phi.putInput(replacement, value));
        }
        normalEdges.forEach(snapshot -> method.disconnect(snapshot.edge()));
        terminator.erase();

        block.append(method.createInstruction(conditional("IFEQ"), List.of(zeroPredicate), List.of()));
        ControlEdge liveEdge = method.connect(block, continuation, EdgeKind.TRUE, "opaque-live", null, 0);
        ControlEdge falseEdge = method.connect(block, falsePath, EdgeKind.FALSE, "opaque-false", null, 0);
        if (terminateFalsePath) {
            falsePath.append(method.createInstruction(CoreOps.UNREACHABLE, List.of(), List.of()));
        }
        return new GuardRewrite(continuation, falsePath, liveEdge, falseEdge);
    }

    static void emitRuntimeCamouflage(IrMethod method, BasicBlock block, IrInstruction anchor,
                                      String configuredSources) {
        Set<String> sources = new LinkedHashSet<>();
        if (configuredSources != null) {
            for (String source : configuredSources.toLowerCase().split("[,;\\s]+")) {
                if (!source.isBlank()) sources.add(source);
            }
        }
        int index = block.instructions().indexOf(anchor);
        if (sources.contains("thread")) {
            IrInstruction current = invoke(method, "java/lang/Thread", "currentThread",
                    "()Ljava/lang/Thread;", "INVOKESTATIC", false, List.of(),
                    List.of(new ReferenceType("java/lang/Thread", Nullability.NON_NULL)));
            IrInstruction id = invoke(method, "java/lang/Thread", "getId", "()J", "INVOKEVIRTUAL",
                    false, List.of(current.result()), List.of(PrimitiveType.LONG));
            block.insert(index++, current);
            block.insert(index++, id);
        }
        if (sources.contains("environment")) {
            IrInstruction runtime = invoke(method, "java/lang/Runtime", "getRuntime",
                    "()Ljava/lang/Runtime;", "INVOKESTATIC", false, List.of(),
                    List.of(new ReferenceType("java/lang/Runtime", Nullability.NON_NULL)));
            IrInstruction processors = invoke(method, "java/lang/Runtime", "availableProcessors", "()I",
                    "INVOKEVIRTUAL", false, List.of(runtime.result()), List.of(PrimitiveType.INT));
            block.insert(index++, runtime);
            block.insert(index++, processors);
        }
        if (sources.contains("time")) {
            block.insert(index, invoke(method, "java/lang/System", "nanoTime", "()J", "INVOKESTATIC",
                    false, List.of(), List.of(PrimitiveType.LONG)));
        }
    }

    static Operation conditional(String opcode) {
        return new Operation(CoreOps.CONDITIONAL_BRANCH, Map.of("condition", IrAttribute.of(opcode)));
    }

    static IrInstruction constant(IrMethod method, long value, PrimitiveType type) {
        return method.createInstruction(new Operation(CoreOps.CONSTANT,
                Map.of("value", IrAttribute.of(value))), List.of(), List.of(type));
    }

    static IrInstruction nullConstant(IrMethod method) {
        return method.createInstruction(new Operation(CoreOps.CONSTANT,
                Map.of("value", IrAttribute.of("null"))), List.of(), List.of(dev.frost.ir.type.SpecialType.NULL));
    }

    static IrInstruction instruction(IrMethod method, dev.frost.ir.model.OperationCode code,
                                     List<Value> operands, dev.frost.ir.type.IrType result,
                                     Map<String, IrAttribute> attributes) {
        return method.createInstruction(new Operation(code, attributes), operands, List.of(result));
    }

    static IrInstruction invoke(IrMethod method, String owner, String name, String descriptor,
                                String invokeKind, boolean isInterface, List<Value> operands,
                                List<dev.frost.ir.type.IrType> results) {
        Map<String, IrAttribute> attributes = new LinkedHashMap<>();
        attributes.put("owner", IrAttribute.of(owner));
        attributes.put("name", IrAttribute.of(name));
        attributes.put("descriptor", IrAttribute.of(descriptor));
        attributes.put("invoke_kind", IrAttribute.of(invokeKind));
        attributes.put("interface", IrAttribute.of(isInterface));
        return method.createInstruction(new Operation(CoreOps.INVOKE, attributes), operands, results);
    }

    static String uniqueBlockName(IrMethod method, String preferred) {
        Set<String> names = new LinkedHashSet<>();
        method.blocks().forEach(block -> names.add(block.name()));
        String sanitized = preferred.replaceAll("[^A-Za-z0-9_$.-]", "_");
        if (sanitized.isBlank() || !Character.isJavaIdentifierStart(sanitized.charAt(0))) sanitized = "frost$" + sanitized;
        String candidate = sanitized;
        int suffix = 0;
        while (names.contains(candidate)) candidate = sanitized + "$" + (++suffix);
        return candidate;
    }

    private static List<EdgeSnapshot> snapshots(List<ControlEdge> edges) {
        List<EdgeSnapshot> result = new ArrayList<>();
        for (ControlEdge edge : List.copyOf(edges)) {
            Map<PhiNode, Value> inputs = new LinkedHashMap<>();
            for (PhiNode phi : edge.target().phis()) {
                phi.input(edge).ifPresent(value -> inputs.put(phi, value));
            }
            result.add(new EdgeSnapshot(edge, inputs));
        }
        return result;
    }

    record GuardRewrite(BasicBlock continuation, BasicBlock falsePath,
                        ControlEdge liveEdge, ControlEdge falseEdge) {}

    private record EdgeSnapshot(ControlEdge edge, Map<PhiNode, Value> phiInputs) {
        private EdgeSnapshot {
            Objects.requireNonNull(edge, "edge");
            phiInputs = Collections.unmodifiableMap(new LinkedHashMap<>(phiInputs));
        }
    }
}
