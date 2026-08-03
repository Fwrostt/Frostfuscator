package dev.frost.ir.model;

import dev.frost.ir.core.IrId;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.type.IrType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Ordered SSA basic block. Valid executable blocks end in exactly one terminator. */
public final class BasicBlock implements IrEntity {
    private final IrMethod method;
    private final IrId id;
    private String name;
    private final List<PhiNode> phis = new ArrayList<>();
    private final List<IrInstruction> instructions = new ArrayList<>();
    private final List<ControlEdge> incomingEdges = new ArrayList<>();
    private final List<ControlEdge> outgoingEdges = new ArrayList<>();
    private final MetadataMap metadata;

    BasicBlock(IrMethod method, IrId id, String name) {
        this.method = Objects.requireNonNull(method, "method");
        this.id = Objects.requireNonNull(id, "id");
        setNameInitial(name);
        metadata = new MetadataMap(method::touch);
    }

    @Override public IrMethod method() { return method; }
    @Override public IrId id() { return id; }
    @Override public MetadataMap metadata() { return metadata; }
    public String name() { return name; }
    public List<PhiNode> phis() { return Collections.unmodifiableList(phis); }
    public List<IrInstruction> instructions() { return Collections.unmodifiableList(instructions); }
    public List<ControlEdge> incomingEdges() { return Collections.unmodifiableList(incomingEdges); }
    public List<ControlEdge> outgoingEdges() { return Collections.unmodifiableList(outgoingEdges); }
    public List<ControlEdge> normalSuccessors() { return outgoingEdges.stream().filter(edge -> !edge.kind().isExceptional()).toList(); }
    public List<ControlEdge> exceptionalSuccessors() { return outgoingEdges.stream().filter(edge -> edge.kind().isExceptional()).toList(); }

    public void setName(String value) {
        String checked = checkedName(value);
        if (!name.equals(checked)) { name = checked; method.touch(); }
    }

    public PhiNode addPhi(IrType type, String debugName) {
        PhiNode phi = new PhiNode(method, method.nextId(), this, type, debugName);
        method.registerEntity(phi);
        phis.add(phi);
        method.touch();
        return phi;
    }

    public void removePhi(PhiNode phi) {
        Objects.requireNonNull(phi, "phi");
        if (phi.block() != this || !phis.contains(phi)) throw new IllegalArgumentException("Phi is not in this block");
        if (phi.result().isUsed()) throw new IllegalStateException("Cannot erase a used phi result: " + phi.result());
        phi.detachAllInputs();
        phis.remove(phi);
        method.unregisterDefinition(phi, List.of(phi.result()));
        method.touch();
    }

    public void append(IrInstruction instruction) { insert(instructions.size(), instruction); }

    public void insert(int index, IrInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction");
        method.requireOwned(instruction);
        if (index < 0 || index > instructions.size()) throw new IndexOutOfBoundsException(index);
        if (instruction.block().isPresent()) throw new IllegalStateException("Instruction already attached");
        if (instruction.isTerminator() && index != instructions.size()) {
            throw new IllegalArgumentException("A terminator must be the final instruction");
        }
        if (!instructions.isEmpty() && instructions.getLast().isTerminator() && index == instructions.size()) {
            throw new IllegalStateException("Block already has a terminator");
        }
        instruction.attach(this);
        instructions.add(index, instruction);
        method.touch();
    }

    public void remove(IrInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction");
        if (instruction.block().orElse(null) != this || !instructions.contains(instruction)) {
            throw new IllegalArgumentException("Instruction is not in this block");
        }
        if (instruction.hasLiveResults()) throw new IllegalStateException("Cannot erase instruction with live results");
        instructions.remove(instruction);
        instruction.detach();
        method.unregisterDefinition(instruction, instruction.results());
        method.touch();
    }

    /** Bulk-only removal after IrMethod has validated external uses and disconnected CFG edges. */
    void detachOperandUsesForRemoval() {
        instructions.forEach(IrInstruction::detach);
        phis.forEach(PhiNode::detachAllInputs);
    }

    void discardDetachedContents() {
        instructions.forEach(instruction -> method.unregisterDefinition(instruction, instruction.results()));
        phis.forEach(phi -> method.unregisterDefinition(phi, List.of(phi.result())));
        instructions.clear();
        phis.clear();
    }

    void discardSelectedDefinitions(Set<IrInstruction> selectedInstructions, Set<PhiNode> selectedPhis) {
        for (IrInstruction instruction : instructions.stream().filter(selectedInstructions::contains).toList()) {
            instructions.remove(instruction);
            instruction.detachFromBlockAfterBulkRemoval();
            method.unregisterDefinition(instruction, instruction.results());
        }
        for (PhiNode phi : phis.stream().filter(selectedPhis::contains).toList()) {
            phis.remove(phi);
            method.unregisterDefinition(phi, List.of(phi.result()));
        }
        method.touch();
    }

    void moveSuffixTo(int fromIndex, BasicBlock target) {
        method.requireOwned(target);
        if (target == this) throw new IllegalArgumentException("Cannot move instructions to the same block");
        if (!target.instructions.isEmpty()) throw new IllegalArgumentException("Target block is not empty");
        if (fromIndex < 0 || fromIndex > instructions.size()) throw new IndexOutOfBoundsException(fromIndex);
        List<IrInstruction> suffix = new ArrayList<>(instructions.subList(fromIndex, instructions.size()));
        instructions.subList(fromIndex, instructions.size()).clear();
        for (IrInstruction instruction : suffix) {
            instruction.moveTo(target);
            target.instructions.add(instruction);
        }
        method.touch();
    }

    public Optional<IrInstruction> terminator() {
        return instructions.isEmpty() || !instructions.getLast().isTerminator()
                ? Optional.empty() : Optional.of(instructions.getLast());
    }

    void addIncoming(ControlEdge edge) { incomingEdges.add(edge); }
    void addOutgoing(ControlEdge edge) { outgoingEdges.add(edge); }
    void removeIncoming(ControlEdge edge) { incomingEdges.remove(edge); }
    void removeOutgoing(ControlEdge edge) { outgoingEdges.remove(edge); }

    private void setNameInitial(String value) { name = checkedName(value); }
    private String checkedName(String value) {
        Objects.requireNonNull(value, "name");
        if (value.isBlank() || !value.matches("[A-Za-z_$][A-Za-z0-9_$.-]*")) {
            throw new IllegalArgumentException("Invalid block name: " + value);
        }
        return value;
    }

    @Override public String toString() { return "^" + name; }
}
