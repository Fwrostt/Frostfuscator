package dev.frost.ir.transform;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeValue;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodParameter;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.bytecode.AsmMetadataKeys;
import dev.frost.ir.type.UninitializedType;
import dev.frost.ir.verify.IrValidator;
import dev.frost.ir.verify.ValidationProfile;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Clones a complete SSA CFG into a caller and replaces one static call with the cloned graph.
 * The caller is expected to discard its import transaction if this operation throws.
 */
public final class IrGraphInliner {
    private static final Set<dev.frost.ir.model.OperationCode> SOURCE_BOUND_OPERATIONS = Set.of(
            CoreOps.INVOKE_DYNAMIC, CoreOps.CONSTANT_DYNAMIC, CoreOps.OPAQUE_PURE_BYTECODE,
            CoreOps.OPAQUE_BYTECODE, CoreOps.OPAQUE_TERMINATOR, CoreOps.NEW_OBJECT, CoreOps.INITIALIZE);
    private static final Set<dev.frost.ir.model.OperationCode> ELIDED_PSEUDO_OPERATIONS = Set.of(
            CoreOps.NOP, CoreOps.LOCAL_WRITE, CoreOps.STACK_PERMUTE);

    public Eligibility check(IrMethod caller, IrInstruction call, IrMethod callee) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(callee, "callee");
        if (caller == callee) return Eligibility.reject("recursive self-inline");
        if (!caller.signature().owner().equals(callee.signature().owner())) {
            return Eligibility.reject("cross-class inlining requires class-initialization modeling");
        }
        if (call.method() != caller || call.block().isEmpty()) return Eligibility.reject("detached or foreign call");
        if (!call.operation().code().equals(CoreOps.INVOKE)) return Eligibility.reject("not a direct invoke");
        if (!stringAttribute(call, "invoke_kind").equals("INVOKESTATIC")) {
            return Eligibility.reject("only static invocation is supported");
        }
        if (call.operands().size() != callee.parameters().size()) return Eligibility.reject("argument count mismatch");
        for (int index = 0; index < call.operands().size(); index++) {
            if (!caller.context().typeLattice().isAssignable(call.operands().get(index).type(),
                    callee.parameters().get(index).value().type())) return Eligibility.reject("argument type mismatch");
        }
        if (!callee.exceptionRegions().isEmpty()) return Eligibility.reject("callee has exception regions");
        BasicBlock callBlock = call.block().orElseThrow();
        if (caller.exceptionRegions().stream().anyMatch(region -> region.protectedBlocks().contains(callBlock))) {
            return Eligibility.reject("call site is protected by an exception region");
        }
        BasicBlock entry = callee.entryBlock().orElse(null);
        if (entry == null || !entry.incomingEdges().isEmpty()) return Eligibility.reject("callee entry has predecessors");
        if (callee.edges().stream().anyMatch(edge -> !edge.values().isEmpty() || edge.kind().isExceptional())) {
            return Eligibility.reject("callee has exceptional or value-carrying edges");
        }
        if (callee.blocks().stream().flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> SOURCE_BOUND_OPERATIONS.contains(instruction.operation().code()))) {
            return Eligibility.reject("callee contains source-bound operations");
        }
        if (callee.blocks().stream().flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> caller.context().schema(instruction.operation().code()).isEmpty())) {
            return Eligibility.reject("caller context does not provide the callee dialect");
        }
        if (callee.blocks().stream().flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.operation().code().equals(CoreOps.CONSTANT)
                        && !instruction.operation().attributes().containsKey("value"))) {
            return Eligibility.reject("callee contains source-bound class or method-handle constants");
        }
        if (callee.blocks().stream().flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.results().stream()).anyMatch(value -> value.type() instanceof UninitializedType)) {
            return Eligibility.reject("callee contains uninitialized verifier values");
        }
        List<IrInstruction> returns = callee.blocks().stream().map(BasicBlock::terminator)
                .flatMap(java.util.Optional::stream)
                .filter(instruction -> instruction.operation().code().equals(CoreOps.RETURN)).toList();
        if (returns.isEmpty()) return Eligibility.reject("callee has no normal return");
        if (returns.stream().anyMatch(ret -> ret.operands().size() != call.results().size())) {
            return Eligibility.reject("return shape mismatch");
        }
        return Eligibility.accept();
    }

    public InlineResult inline(IrMethod caller, IrInstruction call, IrMethod callee, String namePrefix) {
        Eligibility eligibility = check(caller, call, callee);
        if (!eligibility.eligible()) throw new IllegalArgumentException(eligibility.reason());
        String prefix = sanitize(namePrefix);
        BasicBlock callBlock = call.block().orElseThrow();
        BasicBlock continuation = caller.splitBlockAfter(call, prefix + "continuation");

        Map<BasicBlock, BasicBlock> blocks = new IdentityHashMap<>();
        Map<Value, Value> values = new IdentityHashMap<>();
        Map<ControlEdge, ControlEdge> edges = new IdentityHashMap<>();
        List<ReturnSite> returns = new ArrayList<>();
        for (MethodParameter parameter : callee.parameters()) {
            values.put(parameter.value(), call.operands().get(parameter.index()));
        }
        for (BasicBlock source : callee.blocks()) {
            BasicBlock clone = caller.createBlock(prefix + source.name());
            source.metadata().copyPersistentTo(clone.metadata());
            clone.metadata().remove(AsmMetadataKeys.BLOCK_START);
            clone.metadata().remove(AsmMetadataKeys.BLOCK_END);
            blocks.put(source, clone);
        }
        for (BasicBlock source : callee.blocks()) {
            BasicBlock clone = blocks.get(source);
            for (PhiNode phi : source.phis()) {
                PhiNode clonedPhi = clone.addPhi(phi.result().type(), prefix + debugName(phi.result()));
                phi.metadata().copyPersistentTo(clonedPhi.metadata());
                // Callee local/stack slot numbers are not meaningful in the caller. A metadata-free
                // phi receives a fresh lowering local and is materialized by edge parallel copies.
                clonedPhi.metadata().remove(AsmMetadataKeys.PHI_SLOT_KIND);
                clonedPhi.metadata().remove(AsmMetadataKeys.PHI_SLOT_INDEX);
                phi.result().metadata().copyPersistentTo(clonedPhi.result().metadata());
                values.put(phi.result(), clonedPhi.result());
            }
        }
        for (BasicBlock source : callee.blocks()) {
            BasicBlock clone = blocks.get(source);
            for (IrInstruction instruction : source.instructions()) {
                if (instruction.operation().code().equals(CoreOps.RETURN)) {
                    Value returnValue = instruction.operands().isEmpty() ? null : mapped(values, instruction.operands().getFirst());
                    returns.add(new ReturnSite(clone, returnValue));
                    continue;
                }
                if (ELIDED_PSEUDO_OPERATIONS.contains(instruction.operation().code())) continue;
                List<Value> operands = instruction.operands().stream().map(value -> mapped(values, value)).toList();
                IrInstruction cloned = caller.createInstruction(new Operation(instruction.operation().code(),
                        instruction.operation().attributes()), operands,
                        instruction.results().stream().map(Value::type).toList());
                instruction.metadata().copyPersistentTo(cloned.metadata());
                cloned.metadata().remove(AsmMetadataKeys.INSTRUCTION_INDEX);
                clone.append(cloned);
                for (int index = 0; index < instruction.results().size(); index++) {
                    Value original = instruction.results().get(index), result = cloned.results().get(index);
                    original.metadata().copyPersistentTo(result.metadata());
                    if (original.debugName() != null) result.setDebugName(prefix + debugName(original));
                    values.put(original, result);
                }
            }
        }
        for (ControlEdge edge : callee.edges()) {
            ControlEdge cloned = caller.connect(blocks.get(edge.source()), blocks.get(edge.target()), edge.kind(),
                    edge.label(), edge.catchType().orElse(null), edge.priority());
            edge.metadata().copyPersistentTo(cloned.metadata());
            edges.put(edge, cloned);
        }
        for (BasicBlock source : callee.blocks()) {
            BasicBlock clone = blocks.get(source);
            for (int index = 0; index < source.phis().size(); index++) {
                PhiNode original = source.phis().get(index), cloned = clone.phis().get(index);
                original.inputs().forEach((edge, value) -> cloned.putInput(edges.get(edge), mapped(values, value)));
            }
        }

        List<ControlEdge> returnEdges = new ArrayList<>();
        for (ReturnSite site : returns) {
            site.block().append(caller.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
            returnEdges.add(caller.connect(site.block(), continuation, dev.frost.ir.model.EdgeKind.NORMAL));
        }
        if (!call.results().isEmpty()) {
            PhiNode result = continuation.addPhi(call.result().type(), prefix + "result");
            for (int index = 0; index < returns.size(); index++) {
                result.putInput(returnEdges.get(index), Objects.requireNonNull(returns.get(index).value(), "return value"));
            }
            call.result().replaceAllUsesWith(result.result());
        }
        call.erase();
        callBlock.append(caller.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
        caller.connect(callBlock, blocks.get(callee.entryBlock().orElseThrow()), dev.frost.ir.model.EdgeKind.NORMAL);
        new IrValidator().validate(caller, ValidationProfile.STRICT).throwIfInvalid();
        return new InlineResult(blocks.size(), values.size(), returns.size(), continuation);
    }

    private Value mapped(Map<Value, Value> values, Value source) {
        Value mapped = values.get(source);
        if (mapped == null) throw new IllegalStateException("No cloned definition for " + source);
        return mapped;
    }

    private String stringAttribute(IrInstruction instruction, String name) {
        var value = instruction.operation().attributes().get(name);
        return value instanceof dev.frost.ir.model.IrAttribute.StringValue string ? string.value() : "";
    }

    private String sanitize(String value) {
        String sanitized = Objects.requireNonNull(value, "namePrefix").replaceAll("[^A-Za-z0-9_$.-]", "_");
        if (sanitized.isEmpty() || !Character.isJavaIdentifierStart(sanitized.charAt(0))) sanitized = "inline_" + sanitized;
        return sanitized.endsWith(".") ? sanitized : sanitized + ".";
    }

    private String debugName(Value value) { return value.debugName() == null ? "v" + value.id() : value.debugName(); }

    public record Eligibility(boolean eligible, String reason) {
        public static Eligibility accept() { return new Eligibility(true, ""); }
        public static Eligibility reject(String reason) { return new Eligibility(false, Objects.requireNonNull(reason)); }
    }

    public record InlineResult(int clonedBlocks, int clonedValues, int returnSites, BasicBlock continuation) {}
    private record ReturnSite(BasicBlock block, Value value) {}
}
