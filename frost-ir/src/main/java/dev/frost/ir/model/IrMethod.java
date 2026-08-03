package dev.frost.ir.model;

import dev.frost.ir.core.IrContext;
import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.ReferenceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Mutable method-level SSA/CFG consistency domain. */
public final class IrMethod {
    private final IrContext context;
    private final MethodSignature signature;
    private final List<MethodParameter> parameters = new ArrayList<>();
    private final List<BasicBlock> blocks = new ArrayList<>();
    private final List<ControlEdge> edges = new ArrayList<>();
    private final List<ExceptionRegion> exceptionRegions = new ArrayList<>();
    private final Map<IrId, IrEntity> entities = new LinkedHashMap<>();
    private final MetadataMap metadata;
    private long nextId;
    private long revision;
    private int mutationDepth;
    private boolean mutationDirty;
    private BasicBlock entryBlock;

    public IrMethod(IrContext context, MethodSignature signature) {
        this.context = Objects.requireNonNull(context, "context");
        this.signature = Objects.requireNonNull(signature, "signature");
        metadata = new MetadataMap(this::touch);
    }

    public IrContext context() { return context; }
    public MethodSignature signature() { return signature; }
    public MetadataMap metadata() { return metadata; }
    public long revision() { return revision; }
    public List<MethodParameter> parameters() { return Collections.unmodifiableList(parameters); }
    public List<BasicBlock> blocks() { return Collections.unmodifiableList(blocks); }
    public List<ControlEdge> edges() { return Collections.unmodifiableList(edges); }
    public List<ExceptionRegion> exceptionRegions() { return Collections.unmodifiableList(exceptionRegions); }
    public Optional<BasicBlock> entryBlock() { return Optional.ofNullable(entryBlock); }
    public Optional<IrEntity> entity(IrId id) { return Optional.ofNullable(entities.get(id)); }

    public MethodParameter addParameter(String name, IrType type) {
        MethodParameter parameter = new MethodParameter(this, nextId(), parameters.size(), name, type);
        register(parameter);
        parameters.add(parameter);
        touch();
        return parameter;
    }

    public BasicBlock createBlock(String name) {
        BasicBlock block = new BasicBlock(this, nextId(), name);
        register(block);
        blocks.add(block);
        if (entryBlock == null) entryBlock = block;
        touch();
        return block;
    }

    /**
     * Reorders the physical block layout without changing CFG or SSA identity.
     * The supplied order must be an exact identity permutation of the attached blocks and keep
     * the entry block first so method layout remains deterministic for bytecode lowering.
     */
    public void reorderBlocks(List<BasicBlock> order) {
        List<BasicBlock> requested = List.copyOf(Objects.requireNonNull(order, "order"));
        if (requested.size() != blocks.size()) {
            throw new IllegalArgumentException("Block order must contain every attached block exactly once");
        }
        Set<BasicBlock> identities = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BasicBlock block : requested) {
            requireOwned(block);
            if (!blocks.contains(block) || !identities.add(block)) {
                throw new IllegalArgumentException("Block order is not an exact identity permutation");
            }
        }
        if (!requested.isEmpty() && requested.getFirst() != entryBlock) {
            throw new IllegalArgumentException("Entry block must remain first");
        }
        boolean changed = false;
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index) != requested.get(index)) {
                changed = true;
                break;
            }
        }
        if (!changed) return;
        blocks.clear();
        blocks.addAll(requested);
        touch();
    }

    public void setEntryBlock(BasicBlock block) {
        requireOwned(block);
        if (!blocks.contains(block)) throw new IllegalArgumentException("Entry block is not attached to this method");
        if (entryBlock != block) { entryBlock = block; touch(); }
    }

    public IrInstruction createInstruction(Operation operation, List<Value> operands, List<IrType> resultTypes) {
        return createInstruction(operation, operands, ignored -> resultTypes);
    }

    /** Creates result types from the reserved instruction id, enabling allocation-site types. */
    public IrInstruction createInstruction(Operation operation, List<Value> operands,
                                           Function<IrId, List<IrType>> resultTypesFactory) {
        Objects.requireNonNull(operation, "operation");
        operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
        Objects.requireNonNull(resultTypesFactory, "resultTypesFactory");
        operands.forEach(this::requireOwned);
        IrId instructionId = nextId();
        List<IrType> resultTypes = List.copyOf(Objects.requireNonNull(resultTypesFactory.apply(instructionId), "resultTypes"));
        requireOperationShape(operation.code(), operands.size(), resultTypes.size());
        IrInstruction instruction = new IrInstruction(this, instructionId, operation, operands, resultTypes);
        register(instruction);
        touch();
        return instruction;
    }

    public IrInstruction createInstruction(OperationCode code, List<Value> operands, List<IrType> resultTypes) {
        return createInstruction(new Operation(code), operands, resultTypes);
    }

    public ControlEdge connect(BasicBlock source, BasicBlock target, EdgeKind kind) {
        return connect(source, target, kind, "", null, 0);
    }

    public ControlEdge connect(BasicBlock source, BasicBlock target, EdgeKind kind, String label,
                               ReferenceType catchType, int priority) {
        requireOwned(source);
        requireOwned(target);
        ControlEdge edge = new ControlEdge(this, nextId(), source, target, kind, label, catchType, priority);
        register(edge);
        edges.add(edge);
        source.addOutgoing(edge);
        target.addIncoming(edge);
        touch();
        return edge;
    }

    public void disconnect(ControlEdge edge) {
        requireOwned(edge);
        if (!edges.contains(edge)) throw new IllegalArgumentException("Edge is not attached to this method");
        boolean externalEdgeValueUse = edge.values().stream().flatMap(value -> value.result().uses().stream())
                .anyMatch(use -> !(use.user() instanceof PhiNode phi && phi.block() == edge.target()));
        if (externalEdgeValueUse) throw new IllegalStateException("Cannot disconnect an edge with externally used edge values");
        for (PhiNode phi : edge.target().phis()) phi.removeInput(edge);
        edge.source().removeOutgoing(edge);
        edge.target().removeIncoming(edge);
        edges.remove(edge);
        edge.values().forEach(value -> {
            entities.remove(value.result().id(), value.result());
            entities.remove(value.id(), value);
        });
        entities.remove(edge.id());
        touch();
    }

    /** Removes non-entry blocks atomically after proving no value escapes the removed subgraph. */
    public void removeBlocks(Set<BasicBlock> removal) {
        LinkedHashSet<BasicBlock> selected = new LinkedHashSet<>(Objects.requireNonNull(removal, "removal"));
        if (selected.isEmpty()) return;
        selected.forEach(this::requireOwned);
        if (selected.contains(entryBlock)) throw new IllegalArgumentException("Cannot remove the entry block");
        for (BasicBlock block : selected) {
            for (Value value : definedValues(block)) {
                boolean external = value.uses().stream().anyMatch(use -> !useRemovedWithBlocks(use, selected));
                if (external) throw new IllegalStateException("Cannot remove block with externally used value " + value);
            }
        }
        try (Mutation ignored = beginMutation("remove-blocks")) {
            edges.stream().filter(edge -> selected.contains(edge.source()) || selected.contains(edge.target()))
                    .toList().forEach(this::disconnect);
            for (ExceptionRegion region : new ArrayList<>(exceptionRegions)) {
                if (selected.contains(region.handler())) {
                    removeExceptionRegion(region);
                    continue;
                }
                region.removeProtectedBlocks(selected);
                if (region.protectedBlocks().isEmpty()) removeExceptionRegion(region);
            }
            selected.forEach(BasicBlock::detachOperandUsesForRemoval);
            for (BasicBlock block : selected) {
                block.discardDetachedContents();
                blocks.remove(block);
                entities.remove(block.id(), block);
                touch();
            }
        }
    }

    /**
     * Atomically erases a closed set of dead SSA definitions. Uses between selected definitions
     * are detached together, which permits mark/sweep collectors to remove dead phi cycles and
     * cross-block expression chains without relying on a fragile deletion order.
     */
    public void removeDefinitions(Set<IrInstruction> instructions, Set<PhiNode> phis) {
        LinkedHashSet<IrInstruction> deadInstructions = new LinkedHashSet<>(
                Objects.requireNonNull(instructions, "instructions"));
        LinkedHashSet<PhiNode> deadPhis = new LinkedHashSet<>(Objects.requireNonNull(phis, "phis"));
        if (deadInstructions.isEmpty() && deadPhis.isEmpty()) return;
        deadInstructions.forEach(this::requireOwned);
        deadPhis.forEach(this::requireOwned);

        Set<ValueDefinition> definitions = new LinkedHashSet<>();
        definitions.addAll(deadInstructions);
        definitions.addAll(deadPhis);
        for (ValueDefinition definition : definitions) {
            for (Value result : definitionResults(definition)) {
                boolean escapes = result.uses().stream().anyMatch(use -> !definitions.contains(use.user()));
                if (escapes) throw new IllegalStateException("Dead definition has an external use: " + result);
            }
        }

        try (Mutation ignored = beginMutation("remove-definitions")) {
            deadInstructions.forEach(IrInstruction::detachOperandsForBulkRemoval);
            deadPhis.forEach(PhiNode::detachAllInputs);
            blocks.forEach(block -> block.discardSelectedDefinitions(deadInstructions, deadPhis));
        }
    }

    /** Splits a block immediately after {@code instruction}, preserving outgoing edge identity semantics. */
    public BasicBlock splitBlockAfter(IrInstruction instruction, String continuationName) {
        requireOwned(instruction);
        BasicBlock source = instruction.block()
                .orElseThrow(() -> new IllegalArgumentException("Instruction is not attached"));
        int index = source.instructions().indexOf(instruction);
        if (index < 0) throw new IllegalStateException("Instruction owner does not contain it");
        BasicBlock continuation = createBlock(continuationName);
        try (Mutation ignored = beginMutation("split-block")) {
            source.moveSuffixTo(index + 1, continuation);
            for (ExceptionRegion region : exceptionRegions) {
                if (region.protectedBlocks().contains(source)) region.addProtectedBlock(continuation);
            }
            for (ControlEdge oldEdge : List.copyOf(source.outgoingEdges())) {
                Map<PhiNode, Value> phiInputs = new LinkedHashMap<>();
                oldEdge.target().phis().forEach(phi -> phi.input(oldEdge)
                        .ifPresent(value -> phiInputs.put(phi, value)));
                ControlEdge replacement = connect(continuation, oldEdge.target(), oldEdge.kind(),
                        oldEdge.label(), oldEdge.catchType().orElse(null), oldEdge.priority());
                oldEdge.metadata().copyPersistentTo(replacement.metadata());
                for (EdgeValue oldValue : List.copyOf(oldEdge.values())) {
                    EdgeValue newValue = replacement.addValue(oldValue.role(), oldValue.result().type());
                    oldValue.metadata().copyPersistentTo(newValue.metadata());
                    oldValue.result().metadata().copyPersistentTo(newValue.result().metadata());
                    oldValue.result().replaceAllUsesWith(newValue.result());
                }
                disconnect(oldEdge);
                phiInputs.forEach((phi, value) -> phi.putInput(replacement, value));
            }
        }
        return continuation;
    }

    public void removeExceptionRegion(ExceptionRegion region) {
        requireOwned(region);
        if (!exceptionRegions.remove(region)) throw new IllegalArgumentException("Exception region is not attached");
        entities.remove(region.id(), region);
        touch();
    }

    public ExceptionRegion addExceptionRegion(Set<BasicBlock> protectedBlocks, BasicBlock handler,
                                              ReferenceType catchType, int priority) {
        Set<BasicBlock> ordered = new LinkedHashSet<>(protectedBlocks);
        ExceptionRegion region = new ExceptionRegion(this, nextId(), ordered, handler, catchType, priority);
        register(region);
        exceptionRegions.add(region);
        touch();
        return region;
    }

    public Mutation beginMutation(String reason) {
        Objects.requireNonNull(reason, "reason");
        mutationDepth++;
        return new Mutation(this);
    }

    public void requireOwned(IrEntity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.method() != this) {
            throw new IllegalArgumentException("Cross-method IR reference from " + signature.qualifiedName()
                    + " to " + entity.method().signature().qualifiedName());
        }
        if (entities.get(entity.id()) != entity) {
            throw new IllegalArgumentException("Entity " + entity.id() + " is not registered in its claimed owner");
        }
    }

    void requireOperationShape(OperationCode code, int operands, int results) {
        OperationSchema schema = context.schema(code)
                .orElseThrow(() -> new IllegalArgumentException("Unregistered operation: " + code.qualifiedName()));
        if (!schema.acceptsOperandCount(operands) || !schema.acceptsResultCount(results)) {
            throw new IllegalArgumentException(code.qualifiedName() + " expects operands " + schema.minOperands()
                    + ".." + schema.maxOperands() + " and results " + schema.minResults() + ".."
                    + schema.maxResults() + ", got " + operands + " and " + results);
        }
    }

    IrId nextId() { return new IrId(nextId++); }

    Value createValue(IrType type, ValueDefinition definition, int resultIndex) {
        Value value = new Value(this, nextId(), type, definition, resultIndex);
        register(value);
        return value;
    }

    void touch() {
        if (mutationDepth > 0) mutationDirty = true;
        else revision++;
    }

    void registerEntity(IrEntity entity) { register(entity); }

    void unregisterDefinition(IrEntity definition, List<Value> results) {
        results.forEach(value -> {
            if (value.isUsed()) throw new IllegalStateException("Cannot unregister live value " + value);
            entities.remove(value.id(), value);
        });
        entities.remove(definition.id(), definition);
    }

    private List<Value> definedValues(BasicBlock block) {
        List<Value> values = new ArrayList<>();
        block.phis().forEach(phi -> values.add(phi.result()));
        block.instructions().forEach(instruction -> values.addAll(instruction.results()));
        block.outgoingEdges().forEach(edge -> edge.values().forEach(value -> values.add(value.result())));
        return values;
    }

    private List<Value> definitionResults(ValueDefinition definition) {
        if (definition instanceof IrInstruction instruction) return instruction.results();
        if (definition instanceof PhiNode phi) return List.of(phi.result());
        if (definition instanceof EdgeValue edgeValue) return List.of(edgeValue.result());
        if (definition instanceof MethodParameter parameter) return List.of(parameter.value());
        throw new IllegalArgumentException("Unsupported value definition " + definition.getClass().getName());
    }

    private boolean useRemovedWithBlocks(Use use, Set<BasicBlock> removal) {
        if (use.user() instanceof IrInstruction instruction) {
            return instruction.block().map(removal::contains).orElse(false);
        }
        if (use.user() instanceof PhiNode phi) {
            if (removal.contains(phi.block())) return true;
            return phi.inputs().entrySet().stream().anyMatch(entry -> entry.getValue() == use.value()
                    && removal.contains(entry.getKey().source()));
        }
        return false;
    }

    private void register(IrEntity entity) {
        IrEntity previous = entities.putIfAbsent(entity.id(), entity);
        if (previous != null) throw new IllegalStateException("Duplicate IR id " + entity.id());
    }

    private void endMutation() {
        if (mutationDepth <= 0) throw new IllegalStateException("Unbalanced mutation scope");
        mutationDepth--;
        if (mutationDepth == 0 && mutationDirty) {
            mutationDirty = false;
            revision++;
        }
    }

    public static final class Mutation implements AutoCloseable {
        private IrMethod owner;
        private Mutation(IrMethod owner) { this.owner = owner; }
        @Override public void close() {
            if (owner == null) return;
            IrMethod closing = owner;
            owner = null;
            closing.endMutation();
        }
    }
}
