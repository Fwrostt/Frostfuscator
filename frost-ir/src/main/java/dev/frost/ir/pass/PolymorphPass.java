package dev.frost.ir.pass;

import dev.frost.ir.analysis.ConstantFact;
import dev.frost.ir.analysis.DominatorTree;
import dev.frost.ir.analysis.SparseConditionalConstants;
import dev.frost.ir.analysis.StandardAnalyses;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodParameter;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.model.ValueDefinition;
import dev.frost.ir.transform.IrExpressionBuilder;
import dev.frost.ir.type.PrimitiveType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/** Instruction substitutions expressed as typed SSA def-use graphs. */
public final class PolymorphPass implements MethodPass {
    public static final String ID = "frost.obfuscate.polymorph";

    private final Options options;

    public PolymorphPass(Options options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (options.maximum() == 0 || options.probability() == 0) return PassResult.unchanged();
        SplittableRandom random = context.randomFor(id());
        DominatorTree dominators = context.analyses().get(method, StandardAnalyses.DOMINATORS);
        SparseConditionalConstants constants = context.analyses().get(method, StandardAnalyses.SCCP);
        List<Value> anchors = originalDynamicIntegers(method, constants);
        List<IrInstruction> candidates = method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::candidate)
                .filter(instruction -> instruction.result().isUsed())
                .toList();
        int substitutions = 0;
        int crossBlock = 0;

        for (IrInstruction instruction : candidates) {
            if (substitutions >= options.maximum()
                    || random.nextInt(100) >= options.probability()) continue;
            Rewrite rewrite = rewrite(method, instruction, anchors, dominators, random);
            if (rewrite == null) continue;
            copyIdentity(instruction, rewrite.value());
            instruction.result().replaceAllUsesWith(rewrite.value());
            instruction.erase();
            substitutions++;
            if (rewrite.crossBlock()) crossBlock++;
        }

        if (substitutions == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of(
                "substituted", (long) substitutions,
                "cross_block", (long) crossBlock));
    }

    private Rewrite rewrite(IrMethod method, IrInstruction instruction, List<Value> anchors,
                            DominatorTree dominators, SplittableRandom random) {
        if (instruction.operation().code().equals(CoreOps.CONSTANT)) {
            return rewriteConstant(method, instruction, anchors, dominators, random);
        }
        BasicBlock target = instruction.block().orElseThrow();
        int targetIndex = target.instructions().indexOf(instruction);
        List<Value> operands = instruction.operands();
        Value x = operands.getFirst();
        Value y = operands.get(1);
        boolean crossed = false;

        if (instruction.operation().code().equals(CoreOps.ADD)) {
            if (random.nextBoolean()) {
                Placement placement = placement(method, target, instruction, y, dominators);
                IrExpressionBuilder prelude = new IrExpressionBuilder(method, placement.block(), placement.index());
                Value negative = prelude.unary(CoreOps.NEG, y, PrimitiveType.INT);
                IrExpressionBuilder finish = new IrExpressionBuilder(method, target,
                        target.instructions().indexOf(instruction));
                return new Rewrite(finish.binary(CoreOps.SUB, x, negative, PrimitiveType.INT),
                        placement.block() != target);
            }
            int delta = random.nextInt(1, 101);
            Placement leftPlacement = placement(method, target, instruction, x, dominators);
            IrExpressionBuilder leftBuilder = new IrExpressionBuilder(method,
                    leftPlacement.block(), leftPlacement.index());
            Value left = leftBuilder.binary(CoreOps.ADD, x,
                    leftBuilder.constant(delta, PrimitiveType.INT), PrimitiveType.INT);
            Placement rightPlacement = placement(method, target, instruction, y, dominators);
            IrExpressionBuilder rightBuilder = new IrExpressionBuilder(method,
                    rightPlacement.block(), rightPlacement.index());
            Value right = rightBuilder.binary(CoreOps.SUB, y,
                    rightBuilder.constant(delta, PrimitiveType.INT), PrimitiveType.INT);
            IrExpressionBuilder finish = new IrExpressionBuilder(method, target,
                    target.instructions().indexOf(instruction));
            crossed = leftPlacement.block() != target || rightPlacement.block() != target;
            return new Rewrite(finish.binary(CoreOps.ADD, left, right, PrimitiveType.INT), crossed);
        }

        if (instruction.operation().code().equals(CoreOps.SUB)) {
            Placement placement = placement(method, target, instruction, y, dominators);
            IrExpressionBuilder prelude = new IrExpressionBuilder(method, placement.block(), placement.index());
            Value negative = prelude.unary(CoreOps.NEG, y, PrimitiveType.INT);
            IrExpressionBuilder finish = new IrExpressionBuilder(method, target,
                    target.instructions().indexOf(instruction));
            return new Rewrite(finish.binary(CoreOps.ADD, x, negative, PrimitiveType.INT),
                    placement.block() != target);
        }

        if (instruction.operation().code().equals(CoreOps.XOR)) {
            IrExpressionBuilder builder = new IrExpressionBuilder(method, target, targetIndex);
            Value sum = builder.binary(CoreOps.ADD, x, y, PrimitiveType.INT);
            Value common = builder.binary(CoreOps.AND, x, y, PrimitiveType.INT);
            Value doubled = builder.binary(CoreOps.SHL, common,
                    builder.constant(1, PrimitiveType.INT), PrimitiveType.INT);
            return new Rewrite(builder.binary(CoreOps.SUB, sum, doubled, PrimitiveType.INT), false);
        }
        return null;
    }

    private Rewrite rewriteConstant(IrMethod method, IrInstruction instruction, List<Value> anchors,
                                    DominatorTree dominators, SplittableRandom random) {
        int original = (int) ((IrAttribute.LongValue)
                instruction.operation().attributes().get("value")).value();
        BasicBlock target = instruction.block().orElseThrow();
        Value anchor = chooseAnchor(anchors, instruction, target, dominators, random);
        Value dynamicZero = null;
        boolean crossed = false;
        if (anchor != null) {
            Placement placement = placement(method, target, instruction, anchor, dominators);
            IrExpressionBuilder prelude = new IrExpressionBuilder(method, placement.block(), placement.index());
            dynamicZero = prelude.binary(random.nextBoolean() ? CoreOps.SUB : CoreOps.XOR,
                    anchor, anchor, PrimitiveType.INT);
            crossed = placement.block() != target;
        }
        IrExpressionBuilder builder = new IrExpressionBuilder(method, target,
                target.instructions().indexOf(instruction));
        int delta = random.nextInt(1, 11);
        Value expanded = builder.constant(original + delta, PrimitiveType.INT);
        Value recovered = builder.binary(CoreOps.SUB, expanded,
                builder.constant(delta, PrimitiveType.INT), PrimitiveType.INT);
        if (dynamicZero != null) {
            recovered = builder.binary(random.nextBoolean() ? CoreOps.ADD : CoreOps.XOR,
                    recovered, dynamicZero, PrimitiveType.INT);
        }
        return new Rewrite(recovered, crossed);
    }

    private Placement placement(IrMethod method, BasicBlock target, IrInstruction use, Value value,
                                DominatorTree dominators) {
        if (!options.spreadAcrossBlocks()) {
            return new Placement(target, target.instructions().indexOf(use));
        }
        BasicBlock definitionBlock = value.definition().definingBlock()
                .orElseGet(() -> method.entryBlock().orElseThrow());
        if (definitionBlock == target || !dominators.strictlyDominates(definitionBlock, target)) {
            return new Placement(target, target.instructions().indexOf(use));
        }
        return new Placement(definitionBlock, insertionIndex(definitionBlock, value));
    }

    private Value chooseAnchor(List<Value> anchors, IrInstruction use, BasicBlock target,
                               DominatorTree dominators, SplittableRandom random) {
        List<Value> eligible = anchors.stream()
                .filter(value -> dominatesUse(value, use, target, dominators))
                .toList();
        if (eligible.isEmpty()) return null;
        List<Value> strict = eligible.stream()
                .filter(value -> value.definition().definingBlock()
                        .orElseGet(() -> use.method().entryBlock().orElseThrow()) != target)
                .toList();
        List<Value> pool = options.spreadAcrossBlocks() && !strict.isEmpty() ? strict : eligible;
        return pool.get(random.nextInt(pool.size()));
    }

    private boolean dominatesUse(Value value, IrInstruction use, BasicBlock target,
                                 DominatorTree dominators) {
        ValueDefinition definition = value.definition();
        if (definition instanceof MethodParameter) return true;
        BasicBlock definitionBlock = definition.definingBlock().orElse(null);
        if (definitionBlock == null || !dominators.dominates(definitionBlock, target)) return false;
        if (definitionBlock != target || definition instanceof PhiNode) return true;
        return definition instanceof IrInstruction instruction
                && target.instructions().indexOf(instruction) < target.instructions().indexOf(use);
    }

    private List<Value> originalDynamicIntegers(IrMethod method, SparseConditionalConstants constants) {
        List<Value> values = new ArrayList<>();
        method.parameters().stream().map(MethodParameter::value)
                .filter(this::isInt).filter(value -> constants.fact(value) instanceof ConstantFact.Overdefined)
                .forEach(values::add);
        for (BasicBlock block : method.blocks()) {
            block.phis().stream().map(PhiNode::result)
                    .filter(this::isInt).filter(value -> constants.fact(value) instanceof ConstantFact.Overdefined)
                    .forEach(values::add);
            block.instructions().stream()
                    .filter(candidate -> !candidate.operation().code().equals(CoreOps.CONSTANT))
                    .flatMap(candidate -> candidate.results().stream())
                    .filter(this::isInt).filter(value -> constants.fact(value) instanceof ConstantFact.Overdefined)
                    .forEach(values::add);
        }
        return List.copyOf(values);
    }

    private int insertionIndex(BasicBlock block, Value value) {
        if (value.definition() instanceof IrInstruction instruction) {
            int index = block.instructions().indexOf(instruction);
            if (index >= 0) return index + 1;
        }
        return 0;
    }

    private boolean candidate(IrInstruction instruction) {
        if (instruction.results().size() != 1 || instruction.result().type() != PrimitiveType.INT) return false;
        if (instruction.operation().code().equals(CoreOps.CONSTANT)) {
            return instruction.operation().attributes().get("value") instanceof IrAttribute.LongValue value
                    && value.value() >= 0 && value.value() <= 5;
        }
        return instruction.operands().size() == 2
                && instruction.operands().stream().allMatch(this::isInt)
                && (instruction.operation().code().equals(CoreOps.ADD)
                || instruction.operation().code().equals(CoreOps.SUB)
                || instruction.operation().code().equals(CoreOps.XOR));
    }

    private boolean isInt(Value value) {
        return value.type() == PrimitiveType.INT;
    }

    private void copyIdentity(IrInstruction original, Value replacement) {
        if (replacement.definition() instanceof IrInstruction root) {
            original.metadata().copyPersistentTo(root.metadata());
        }
        replacement.setDebugName(original.result().debugName());
    }

    private record Placement(BasicBlock block, int index) {}
    private record Rewrite(Value value, boolean crossBlock) {}

    public record Options(int probability, int maximum, boolean spreadAcrossBlocks) {
        public Options {
            if (probability < 0 || probability > 100) throw new IllegalArgumentException("probability");
            if (maximum < 0) throw new IllegalArgumentException("maximum");
        }
    }
}
