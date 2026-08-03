package dev.frost.ir.verify;

import dev.frost.ir.analysis.DominatorTree;
import dev.frost.ir.analysis.EdgePolicy;
import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.core.IrId;
import dev.frost.ir.core.SourcePosition;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.EdgeValue;
import dev.frost.ir.model.ExceptionRegion;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodParameter;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.OperationSchema;
import dev.frost.ir.model.OperationTrait;
import dev.frost.ir.model.OperationViolation;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Use;
import dev.frost.ir.model.Value;
import dev.frost.ir.model.ValueDefinition;
import dev.frost.ir.model.ValueUser;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.SpecialType;
import dev.frost.ir.type.UninitializedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Aggregating validator: it reports independent faults in one run instead of failing at the first. */
public final class IrValidator {
    public ValidationReport validate(IrMethod method, ValidationProfile profile) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(profile, "profile");
        State state = new State(method, profile);
        state.validate();
        return new ValidationReport(state.diagnostics);
    }

    private static final class State {
        private static final int ACC_NATIVE = 0x0100;
        private static final int ACC_ABSTRACT = 0x0400;

        private final IrMethod method;
        private final ValidationProfile profile;
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final Set<Value> values = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Use> observedUses = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<IrInstruction, Integer> positions = new IdentityHashMap<>();
        private DominatorTree dominators;

        private State(IrMethod method, ValidationProfile profile) {
            this.method = method;
            this.profile = profile;
        }

        private void validate() {
            boolean hasCode = (method.signature().access() & (ACC_NATIVE | ACC_ABSTRACT)) == 0;
            if (hasCode && method.blocks().isEmpty()) error("method.no-blocks", "Executable method has no basic blocks", null);
            if (!hasCode && !method.blocks().isEmpty()) error("method.illegal-body", "Abstract/native method has an IR body", null);
            if (!method.blocks().isEmpty() && method.entryBlock().isEmpty()) error("method.no-entry", "Method has blocks but no entry block", null);

            Set<String> names = new LinkedHashSet<>();
            method.parameters().forEach(this::validateParameter);
            for (BasicBlock block : method.blocks()) {
                if (!names.add(block.name())) error("block.duplicate-name", "Duplicate block name: " + block.name(), block.id());
                validateBlock(block, hasCode);
            }
            method.edges().forEach(this::validateEdge);
            method.exceptionRegions().forEach(this::validateExceptionRegion);
            validateUses();
            if (profile != ValidationProfile.STRUCTURAL && method.entryBlock().isPresent()) validateDominance();
            if (profile == ValidationProfile.LOWERABLE) validateLowerability();
        }

        private void validateParameter(MethodParameter parameter) {
            owned(parameter);
            owned(parameter.value());
            values.add(parameter.value());
            if (parameter.value().definition() != parameter) {
                error("parameter.definition", "Parameter value has the wrong definition", parameter.id());
            }
        }

        private void validateBlock(BasicBlock block, boolean hasCode) {
            owned(block);
            if (block == method.entryBlock().orElse(null)
                    && block.incomingEdges().stream().anyMatch(edge -> !edge.kind().isExceptional())) {
                error("entry.predecessor", "Entry block has a normal predecessor", block.id());
            }
            if (hasCode && profile != ValidationProfile.STRUCTURAL && block.terminator().isEmpty()) {
                error("block.no-terminator", "Block does not end in a terminator", block.id());
            }

            for (PhiNode phi : block.phis()) validatePhi(block, phi);
            for (int index = 0; index < block.instructions().size(); index++) {
                IrInstruction instruction = block.instructions().get(index);
                positions.put(instruction, index);
                validateInstruction(block, instruction, index);
            }
            validateTerminatorEdges(block);
        }

        private void validatePhi(BasicBlock block, PhiNode phi) {
            owned(phi);
            owned(phi.result());
            values.add(phi.result());
            if (phi.block() != block || phi.definingBlock().orElse(null) != block) {
                error("phi.owner", "Phi defining block disagrees with containing block", phi.id());
            }
            if (phi.result().definition() != phi) error("phi.definition", "Phi result has the wrong definition", phi.id());

            Set<ControlEdge> actual = phi.inputs().keySet();
            Set<ControlEdge> expected = new LinkedHashSet<>(block.incomingEdges());
            if (profile != ValidationProfile.STRUCTURAL && !actual.equals(expected)) {
                error("phi.incomplete", "Phi inputs must exactly match incoming CFG edges", phi.id(),
                        Map.of("expected", ids(expected), "actual", ids(actual)));
            }
            phi.inputs().forEach((edge, value) -> {
                owned(edge);
                owned(value);
                values.add(value);
                if (edge.target() != block) error("phi.edge-target", "Phi input edge targets another block", phi.id());
                if (!method.context().typeLattice().isAssignable(value.type(), phi.result().type())) {
                    error("phi.type", "Phi input type " + value.type().displayName() + " is not assignable to "
                            + phi.result().type().displayName(), phi.id());
                }
            });
            observeUses(phi);
        }

        private void validateInstruction(BasicBlock block, IrInstruction instruction, int index) {
            owned(instruction);
            if (instruction.block().orElse(null) != block || instruction.definingBlock().orElse(null) != block) {
                error("instruction.owner", "Instruction defining block disagrees with containing block", instruction.id());
            }
            OperationSchema schema = method.context().schema(instruction.operation().code()).orElse(null);
            if (schema == null) {
                error("operation.unregistered", "Unregistered operation " + instruction.operation().code().qualifiedName(), instruction.id());
            } else {
                if (!schema.acceptsOperandCount(instruction.operands().size())) {
                    error("operation.operands", "Operation operand count violates its schema", instruction.id());
                }
                if (!schema.acceptsResultCount(instruction.results().size())) {
                    error("operation.results", "Operation result count violates its schema", instruction.id());
                }
                if (schema.hasTrait(OperationTrait.TERMINATOR) && index != block.instructions().size() - 1) {
                    error("terminator.position", "Terminator is not the final instruction", instruction.id());
                }
                if (!schema.hasTrait(OperationTrait.TERMINATOR) && index == block.instructions().size() - 1
                        && profile != ValidationProfile.STRUCTURAL) {
                    error("terminator.missing", "Final instruction is not a terminator", instruction.id());
                }
                for (var verifier : schema.verifiers()) {
                    try {
                        List<OperationViolation> violations = verifier.verify(instruction);
                        if (violations == null) {
                            error("operation.verifier-null", "Operation verifier returned null", instruction.id());
                        } else violations.forEach(violation -> error("operation." + violation.code(),
                                violation.message(), instruction.id()));
                    } catch (RuntimeException exception) {
                        error("operation.verifier-failed", "Operation verifier failed: " + exception.getMessage(), instruction.id());
                    }
                }
            }
            for (int resultIndex = 0; resultIndex < instruction.results().size(); resultIndex++) {
                Value value = instruction.results().get(resultIndex);
                owned(value);
                values.add(value);
                if (value.definition() != instruction || value.resultIndex() != resultIndex) {
                    error("result.definition", "Instruction result definition/index is inconsistent", value.id());
                }
            }
            instruction.operands().forEach(value -> { owned(value); values.add(value); });
            validateCoreTypes(instruction);
            observeUses(instruction);
        }

        private void validateCoreTypes(IrInstruction instruction) {
            OperationCode code = instruction.operation().code();
            List<Value> operands = instruction.operands();
            List<Value> results = instruction.results();
            if (Set.of(CoreOps.ADD, CoreOps.SUB, CoreOps.MUL, CoreOps.DIV, CoreOps.REM).contains(code)
                    && operands.size() == 2 && results.size() == 1) {
                if (!numeric(operands.get(0).type()) || !sameComputation(operands.get(0).type(), operands.get(1).type())
                        || !sameComputation(operands.get(0).type(), results.getFirst().type())) {
                    typeError(instruction, "Arithmetic operands and result must share one numeric computational type");
                }
            } else if (Set.of(CoreOps.AND, CoreOps.OR, CoreOps.XOR).contains(code)
                    && operands.size() == 2 && results.size() == 1) {
                if (!integral(operands.get(0).type()) || !sameComputation(operands.get(0).type(), operands.get(1).type())
                        || !sameComputation(operands.get(0).type(), results.getFirst().type())) {
                    typeError(instruction, "Bitwise operands and result must share one integral computational type");
                }
            } else if (Set.of(CoreOps.SHL, CoreOps.SHR, CoreOps.USHR).contains(code)
                    && operands.size() == 2 && results.size() == 1) {
                if (!integral(operands.get(0).type()) || !intFamily(operands.get(1).type())
                        || !sameComputation(operands.get(0).type(), results.getFirst().type())) {
                    typeError(instruction, "Shift value/result must be integral and shift distance must be int-family");
                }
            } else if (code.equals(CoreOps.NEG) && operands.size() == 1 && results.size() == 1) {
                if (!numeric(operands.getFirst().type()) || !sameComputation(operands.getFirst().type(), results.getFirst().type())) {
                    typeError(instruction, "Negation operand and result must share one numeric computational type");
                }
            } else if (code.equals(CoreOps.CONVERT) && operands.size() == 1 && results.size() == 1) {
                if (!numeric(operands.getFirst().type()) || !numeric(results.getFirst().type())) {
                    typeError(instruction, "Conversion requires numeric operand and result types");
                }
            } else if (code.equals(CoreOps.COMPARE) && operands.size() == 2 && results.size() == 1) {
                if (!numeric(operands.get(0).type()) || !sameComputation(operands.get(0).type(), operands.get(1).type())
                        || !intFamily(results.getFirst().type())) {
                    typeError(instruction, "Comparison requires equal numeric operands and an int result");
                }
            } else if (code.equals(CoreOps.COPY) && operands.size() == 1 && results.size() == 1) {
                if (!method.context().typeLattice().isAssignable(operands.getFirst().type(), results.getFirst().type())) {
                    typeError(instruction, "Copy operand is not assignable to its result type");
                }
            } else if (code.equals(CoreOps.SELECT) && operands.size() == 3 && results.size() == 1) {
                if (!intFamily(operands.getFirst().type())
                        || !method.context().typeLattice().isAssignable(operands.get(1).type(), results.getFirst().type())
                        || !method.context().typeLattice().isAssignable(operands.get(2).type(), results.getFirst().type())) {
                    typeError(instruction, "Select needs an int condition and assignable alternatives");
                }
            } else if ((code.equals(CoreOps.ARRAY_LOAD) || code.equals(CoreOps.ARRAY_STORE)) && operands.size() >= 2) {
                if (!operands.getFirst().type().isReferenceLike() || !intFamily(operands.get(1).type())) {
                    typeError(instruction, "Array access needs a reference-like array and int index");
                }
            } else if (code.equals(CoreOps.ARRAY_LENGTH) && operands.size() == 1 && results.size() == 1) {
                if (!operands.getFirst().type().isReferenceLike() || !intFamily(results.getFirst().type())) {
                    typeError(instruction, "array_length needs a reference-like array and int result");
                }
            } else if ((code.equals(CoreOps.CHECK_CAST) || code.equals(CoreOps.INSTANCE_OF)) && !operands.isEmpty()) {
                if (!operands.getFirst().type().isReferenceLike()) typeError(instruction, "Type test/cast operand must be reference-like");
            } else if ((code.equals(CoreOps.MONITOR_ENTER) || code.equals(CoreOps.MONITOR_EXIT)
                    || code.equals(CoreOps.THROW)) && operands.size() == 1) {
                if (!operands.getFirst().type().isReferenceLike()) typeError(instruction, "Monitor/throw operand must be reference-like");
            } else if (code.equals(CoreOps.SWITCH) && operands.size() == 1) {
                if (!intFamily(operands.getFirst().type())) typeError(instruction, "Switch selector must be int-family");
            } else if (code.equals(CoreOps.CONDITIONAL_BRANCH)) {
                validateConditionTypes(instruction);
            } else if (code.equals(CoreOps.RETURN)) {
                IrType expected = method.signature().type().returnType();
                if (expected == PrimitiveType.VOID ? !operands.isEmpty()
                        : operands.size() != 1 || !method.context().typeLattice().isAssignable(operands.getFirst().type(), expected)) {
                    typeError(instruction, "Return operand does not match the method return type");
                }
            } else if (code.equals(CoreOps.INITIALIZE) && !operands.isEmpty() && results.size() == 1) {
                IrType receiver = operands.getFirst().type();
                // Stack-to-SSA alias replacement can conservatively widen a verifier
                // uninitialized value to its reference type across DUP/phi shapes. The
                // preserved constructor operation and final ASM verification remain exact.
                if (!(receiver instanceof UninitializedType) && receiver != SpecialType.UNINITIALIZED_THIS
                        && !receiver.isReferenceLike()) {
                    typeError(instruction, "initialize receiver must be an uninitialized verifier value");
                }
                if (!results.getFirst().type().isReferenceLike()) typeError(instruction, "initialize result must be reference-like");
            }
        }

        private void validateConditionTypes(IrInstruction instruction) {
            String condition = instruction.operation().attributes().get("condition") instanceof IrAttribute.StringValue value
                    ? value.value() : "";
            boolean references = condition.equals("IFNULL") || condition.equals("IFNONNULL")
                    || condition.equals("IF_ACMPEQ") || condition.equals("IF_ACMPNE");
            boolean valid = references ? instruction.operands().stream().allMatch(value -> value.type().isReferenceLike())
                    : instruction.operands().stream().allMatch(value -> intFamily(value.type()));
            if (!valid) typeError(instruction, references
                    ? "Reference condition requires reference-like operands" : "Integer condition requires int-family operands");
        }

        private boolean numeric(IrType type) { return type instanceof PrimitiveType primitive && primitive != PrimitiveType.VOID; }
        private boolean integral(IrType type) {
            return type instanceof PrimitiveType primitive && primitive != PrimitiveType.FLOAT
                    && primitive != PrimitiveType.DOUBLE && primitive != PrimitiveType.VOID;
        }
        private boolean intFamily(IrType type) {
            return type instanceof PrimitiveType primitive && primitive.computationalType() == PrimitiveType.INT;
        }
        private boolean sameComputation(IrType left, IrType right) {
            return left instanceof PrimitiveType a && right instanceof PrimitiveType b
                    && a.computationalType() == b.computationalType();
        }
        private void typeError(IrInstruction instruction, String message) {
            error("operation.type", message, instruction.id());
        }

        private void validateEdge(ControlEdge edge) {
            owned(edge);
            owned(edge.source());
            owned(edge.target());
            if (!edge.source().outgoingEdges().contains(edge)) error("edge.source-incidence", "Source omits outgoing edge", edge.id());
            if (!edge.target().incomingEdges().contains(edge)) error("edge.target-incidence", "Target omits incoming edge", edge.id());
            long sourceCopies = edge.source().outgoingEdges().stream().filter(candidate -> candidate == edge).count();
            long targetCopies = edge.target().incomingEdges().stream().filter(candidate -> candidate == edge).count();
            if (sourceCopies != 1 || targetCopies != 1) error("edge.duplicate-incidence", "Edge incidence is not unique", edge.id());
            for (EdgeValue edgeValue : edge.values()) {
                owned(edgeValue);
                owned(edgeValue.result());
                values.add(edgeValue.result());
                if (edgeValue.edge() != edge || edgeValue.result().definition() != edgeValue) {
                    error("edge-value.definition", "Edge value has inconsistent ownership or definition", edgeValue.id());
                }
            }
        }

        private void validateExceptionRegion(ExceptionRegion region) {
            owned(region);
            owned(region.handler());
            region.protectedBlocks().forEach(this::owned);
            for (BasicBlock block : region.protectedBlocks()) {
                boolean matching = block.outgoingEdges().stream().anyMatch(edge -> edge.target() == region.handler()
                        && edge.kind().isExceptional() && edge.priority() == region.priority()
                        && Objects.equals(edge.catchType().orElse(null), region.catchType().orElse(null)));
                if (profile != ValidationProfile.STRUCTURAL && !matching) {
                    error("exception.missing-edge", "Protected block has no matching exceptional edge", region.id(),
                            Map.of("block", block.id().toString()));
                }
            }
        }

        private void validateTerminatorEdges(BasicBlock block) {
            IrInstruction terminator = block.terminator().orElse(null);
            if (terminator == null) return;
            OperationCode code = terminator.operation().code();
            List<ControlEdge> normal = block.normalSuccessors();
            if (code.equals(CoreOps.BRANCH) && normal.size() != 1) {
                error("branch.successors", "branch requires exactly one normal successor", terminator.id());
            } else if (code.equals(CoreOps.CONDITIONAL_BRANCH)) {
                long trueEdges = normal.stream().filter(edge -> edge.kind() == EdgeKind.TRUE).count();
                long falseEdges = normal.stream().filter(edge -> edge.kind() == EdgeKind.FALSE).count();
                if (normal.size() != 2 || trueEdges != 1 || falseEdges != 1) {
                    error("conditional.successors", "conditional_branch requires one true and one false edge", terminator.id());
                }
            } else if (code.equals(CoreOps.SWITCH)) {
                long defaults = normal.stream().filter(edge -> edge.kind() == EdgeKind.SWITCH_DEFAULT).count();
                if (defaults != 1 || normal.stream().anyMatch(edge -> edge.kind() != EdgeKind.SWITCH_CASE
                        && edge.kind() != EdgeKind.SWITCH_DEFAULT)) {
                    error("switch.successors", "switch requires one default and zero or more case edges", terminator.id());
                }
            } else if ((code.equals(CoreOps.RETURN) || code.equals(CoreOps.THROW)
                    || code.equals(CoreOps.UNREACHABLE)) && !normal.isEmpty()) {
                error("exit.successors", "Exit terminator has normal successors", terminator.id());
            }
        }

        private void observeUses(ValueUser user) {
            List<Use> uses = user.operandUses();
            for (int position = 0; position < uses.size(); position++) {
                Use use = uses.get(position);
                if (!use.isAttached()) error("use.detached", "Attached user exposes a detached use", user.id());
                if (use.user() != user) error("use.user", "Use points at a different user", user.id());
                if (!observedUses.add(use)) error("use.duplicate", "Use appears in more than one operand slot", user.id());
                if (!use.value().uses().contains(use)) error("use.backlink", "Value def-use set omits operand use", user.id());
            }
        }

        private void validateUses() {
            for (Value value : values) {
                for (Use use : value.uses()) {
                    if (use.value() != value) error("use.value", "Value use set contains a use of another value", value.id());
                    if (!observedUses.contains(use)) error("use.orphan", "Value has a use not exposed by an attached user", value.id());
                }
            }
        }

        private void validateDominance() {
            dominators = DominatorTree.compute(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
            for (BasicBlock block : method.blocks()) {
                for (IrInstruction instruction : block.instructions()) {
                    for (Value operand : instruction.operands()) validateUseDominance(operand, block, positions.get(instruction), instruction.id());
                }
                for (PhiNode phi : block.phis()) {
                    phi.inputs().forEach((edge, value) -> validatePhiDominance(value, edge, phi.id()));
                }
            }
        }

        private void validateUseDominance(Value value, BasicBlock userBlock, int userPosition, IrId userId) {
            ValueDefinition definition = value.definition();
            if (definition instanceof MethodParameter) return;
            BasicBlock definitionBlock = definition.definingBlock().orElse(null);
            if (definitionBlock == null) { error("ssa.orphan-definition", "Operand definition is not attached", userId); return; }
            if (definitionBlock == userBlock) {
                if (definition instanceof IrInstruction instruction
                        && positions.getOrDefault(instruction, Integer.MAX_VALUE) >= userPosition) {
                    error("ssa.use-before-def", "Instruction uses a value before its definition", userId,
                            Map.of("value", value.id().toString()));
                }
            } else if (!dominators.dominates(definitionBlock, userBlock)) {
                error("ssa.not-dominated", "Value definition does not dominate its use", userId,
                        Map.of("value", value.id().toString(), "definitionBlock", definitionBlock.name(),
                                "useBlock", userBlock.name()));
            }
        }

        private void validatePhiDominance(Value value, ControlEdge edge, IrId phiId) {
            ValueDefinition definition = value.definition();
            if (definition instanceof MethodParameter) return;
            BasicBlock definitionBlock = definition.definingBlock().orElse(null);
            if (definitionBlock == null) { error("ssa.orphan-definition", "Phi input definition is not attached", phiId); return; }
            BasicBlock predecessor = edge.source();
            if (definitionBlock != predecessor && !dominators.dominates(definitionBlock, predecessor)) {
                error("ssa.phi-not-dominated", "Phi input does not dominate its incoming edge", phiId,
                        Map.of("value", value.id().toString(), "edge", edge.id().toString()));
            }
        }

        private void validateLowerability() {
            for (BasicBlock block : method.blocks()) {
                for (IrInstruction instruction : block.instructions()) {
                    if (instruction.operation().code().equals(CoreOps.OPAQUE_BYTECODE)
                            || instruction.operation().code().equals(CoreOps.OPAQUE_PURE_BYTECODE)
                            || instruction.operation().code().equals(CoreOps.OPAQUE_TERMINATOR)) {
                        error("lowering.opaque", "Opaque bytecode operation has no registered lowering", instruction.id());
                    }
                }
            }
        }

        private void owned(dev.frost.ir.model.IrEntity entity) {
            try { method.requireOwned(entity); }
            catch (IllegalArgumentException exception) { error("ownership", exception.getMessage(), entity.id()); }
        }

        private String ids(Set<? extends dev.frost.ir.model.IrEntity> entities) {
            return entities.stream().map(entity -> entity.id().toString()).toList().toString();
        }

        private void error(String code, String message, IrId entity) { error(code, message, entity, Map.of()); }
        private void error(String code, String message, IrId entity, Map<String, String> details) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, code, message, entity,
                    SourcePosition.UNKNOWN, new LinkedHashMap<>(details)));
        }
    }
}
