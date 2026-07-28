package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

public class ControlFlowShufflingTransformer extends Transformer {

    @Override
    public String getName() {
        return "control-flow-shuffling";
    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        LongAdder shuffledMethods = new LongAdder();
        int probability = config.getOptionInt("probability", 50);
        long globalSeed = config.getOptionLong("seed", 1337L);

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                return;
            }
            if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
                return;
            }

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() < 6) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) continue; // Keep exception handlers safe

                Random random = new Random(globalSeed ^ classNode.name.hashCode() ^ method.name.hashCode());
                if (random.nextInt(100) >= probability) continue;

                if (shuffleControlFlow(method, random)) {
                    shuffledMethods.increment();
                }
            }
        });

        Logger.info("Shuffled control flow basic blocks in {} method(s)", shuffledMethods.sum());
    }

    private boolean shuffleControlFlow(MethodNode method, Random random) {
        InsnList insns = method.instructions;
        List<BasicBlock> blocks = divideIntoBasicBlocks(insns);
        if (blocks.size() <= 2) return false;

        // Ensure every block (except return/throw) ends with an explicit unconditional jump
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock current = blocks.get(i);
            AbstractInsnNode last = current.instructions.isEmpty() ? null : current.instructions.get(current.instructions.size() - 1);

            boolean isTerminal = last != null && (last.getOpcode() == Opcodes.GOTO
                    || (last.getOpcode() >= Opcodes.IRETURN && last.getOpcode() <= Opcodes.RETURN)
                    || last.getOpcode() == Opcodes.ATHROW);

            if (!isTerminal && i + 1 < blocks.size()) {
                BasicBlock next = blocks.get(i + 1);
                LabelNode nextLabel = next.getEntryLabel();
                current.instructions.add(new JumpInsnNode(Opcodes.GOTO, nextLabel));
            }
        }

        // Shuffle all blocks except the first entry block
        BasicBlock entryBlock = blocks.get(0);
        List<BasicBlock> rest = new ArrayList<>(blocks.subList(1, blocks.size()));
        Collections.shuffle(rest, random);

        InsnList newInsns = new InsnList();
        for (AbstractInsnNode node : entryBlock.instructions) {
            insns.remove(node);
            newInsns.add(node);
        }
        for (BasicBlock block : rest) {
            for (AbstractInsnNode node : block.instructions) {
                insns.remove(node);
                newInsns.add(node);
            }
        }

        method.instructions = newInsns;
        return true;
    }

    private List<BasicBlock> divideIntoBasicBlocks(InsnList insns) {
        List<BasicBlock> blocks = new ArrayList<>();
        BasicBlock current = new BasicBlock();
        blocks.add(current);

        for (AbstractInsnNode insn : insns.toArray()) {
            if (insn instanceof LabelNode labelNode) {
                if (!current.instructions.isEmpty()) {
                    current = new BasicBlock();
                    blocks.add(current);
                }
                current.setEntryLabel(labelNode);
                current.instructions.add(insn);
            } else if (isBranchOrJump(insn)) {
                current.instructions.add(insn);
                current = new BasicBlock();
                blocks.add(current);
            } else {
                current.instructions.add(insn);
            }
        }

        blocks.removeIf(b -> b.instructions.isEmpty());
        return blocks;
    }

    private boolean isBranchOrJump(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= Opcodes.IFEQ && op <= Opcodes.GOTO)
                || op == Opcodes.TABLESWITCH
                || op == Opcodes.LOOKUPSWITCH
                || (op >= Opcodes.IRETURN && op <= Opcodes.RETURN)
                || op == Opcodes.ATHROW;
    }

    private static class BasicBlock {
        private LabelNode entryLabel;
        private final List<AbstractInsnNode> instructions = new ArrayList<>();

        public LabelNode getEntryLabel() {
            if (entryLabel == null) {
                entryLabel = new LabelNode();
                instructions.add(0, entryLabel);
            }
            return entryLabel;
        }

        public void setEntryLabel(LabelNode label) {
            this.entryLabel = label;
        }
    }
}
