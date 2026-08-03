package dev.frost.ir.bytecode;

import dev.frost.ir.model.BasicBlock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.tree.AbstractInsnNode;

/** Program-point frame states produced by the SSA lifter. All ASM keys use source-tree identity. */
public final class FrameStateMap {
    private final Map<BasicBlock, JvmFrameState> blockEntries;
    private final Map<BasicBlock, JvmFrameState> blockExits;
    private final Map<AbstractInsnNode, JvmFrameState> beforeInstructions;
    private final Map<AbstractInsnNode, JvmFrameState> afterInstructions;

    FrameStateMap(Map<BasicBlock, JvmFrameState> blockEntries,
                  Map<BasicBlock, JvmFrameState> blockExits,
                  Map<AbstractInsnNode, JvmFrameState> beforeInstructions,
                  Map<AbstractInsnNode, JvmFrameState> afterInstructions) {
        blockEntries = immutableIdentity(blockEntries);
        blockExits = immutableIdentity(blockExits);
        beforeInstructions = immutableIdentity(beforeInstructions);
        afterInstructions = immutableIdentity(afterInstructions);
        this.blockEntries = blockEntries;
        this.blockExits = blockExits;
        this.beforeInstructions = beforeInstructions;
        this.afterInstructions = afterInstructions;
    }

    public Optional<JvmFrameState> entry(BasicBlock block) { return Optional.ofNullable(blockEntries.get(block)); }
    public Optional<JvmFrameState> exit(BasicBlock block) { return Optional.ofNullable(blockExits.get(block)); }
    public Optional<JvmFrameState> before(AbstractInsnNode instruction) { return Optional.ofNullable(beforeInstructions.get(instruction)); }
    public Optional<JvmFrameState> after(AbstractInsnNode instruction) { return Optional.ofNullable(afterInstructions.get(instruction)); }
    public Map<BasicBlock, JvmFrameState> blockEntries() { return blockEntries; }
    public Map<BasicBlock, JvmFrameState> blockExits() { return blockExits; }

    private static <K, V> Map<K, V> immutableIdentity(Map<K, V> source) {
        Map<K, V> copy = new IdentityHashMap<>();
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }
}
