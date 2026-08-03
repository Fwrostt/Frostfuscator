package dev.frost.ir.bytecode;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.IrInstruction;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.tree.AbstractInsnNode;

/** Identity-based bidirectional mapping between one imported ASM tree and Frost-IR. */
public final class AsmSourceMap {
    private final Map<AbstractInsnNode, BasicBlock> nodeBlocks;
    private final Map<AbstractInsnNode, IrInstruction> nodeInstructions;
    private final Map<AbstractInsnNode, java.util.List<IrInstruction>> allNodeInstructions;
    private final Map<IrInstruction, AbstractInsnNode> instructionNodes;

    AsmSourceMap(Map<AbstractInsnNode, BasicBlock> nodeBlocks,
                 Map<AbstractInsnNode, IrInstruction> nodeInstructions,
                 Map<IrInstruction, AbstractInsnNode> instructionNodes) {
        this.nodeBlocks = immutableIdentity(nodeBlocks);
        this.nodeInstructions = immutableIdentity(nodeInstructions);
        Map<AbstractInsnNode, java.util.List<IrInstruction>> expanded = new IdentityHashMap<>();
        nodeInstructions.forEach((node, instruction) -> expanded.put(node, java.util.List.of(instruction)));
        this.allNodeInstructions = immutableIdentity(expanded);
        this.instructionNodes = immutableIdentity(instructionNodes);
    }

    AsmSourceMap(Map<AbstractInsnNode, BasicBlock> nodeBlocks,
                 Map<AbstractInsnNode, java.util.List<IrInstruction>> allNodeInstructions,
                 Map<IrInstruction, AbstractInsnNode> instructionNodes, boolean expanded) {
        this.nodeBlocks = immutableIdentity(nodeBlocks);
        this.allNodeInstructions = immutableIdentityLists(allNodeInstructions);
        Map<AbstractInsnNode, IrInstruction> primary = new IdentityHashMap<>();
        allNodeInstructions.forEach((node, instructions) -> {
            if (!instructions.isEmpty()) primary.put(node, instructions.getLast());
        });
        this.nodeInstructions = immutableIdentity(primary);
        this.instructionNodes = immutableIdentity(instructionNodes);
    }

    public Optional<BasicBlock> block(AbstractInsnNode node) { return Optional.ofNullable(nodeBlocks.get(node)); }
    public Optional<IrInstruction> instruction(AbstractInsnNode node) { return Optional.ofNullable(nodeInstructions.get(node)); }
    public java.util.List<IrInstruction> instructions(AbstractInsnNode node) {
        return allNodeInstructions.getOrDefault(node, java.util.List.of());
    }
    public Optional<AbstractInsnNode> source(IrInstruction instruction) { return Optional.ofNullable(instructionNodes.get(instruction)); }
    public Map<AbstractInsnNode, BasicBlock> nodeBlocks() { return nodeBlocks; }
    public Map<AbstractInsnNode, IrInstruction> nodeInstructions() { return nodeInstructions; }

    private static <K, V> Map<K, V> immutableIdentity(Map<K, V> source) {
        Map<K, V> copy = new IdentityHashMap<>();
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }

    private static <K, V> Map<K, java.util.List<V>> immutableIdentityLists(Map<K, java.util.List<V>> source) {
        Map<K, java.util.List<V>> copy = new IdentityHashMap<>();
        source.forEach((key, value) -> copy.put(key, java.util.List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }
}
