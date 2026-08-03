package dev.frost.ir.pass;

import dev.frost.ir.analysis.DominatorTree;
import dev.frost.ir.analysis.ConstantFact;
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

/**
 * Reconstructs integer constants from randomized shares entangled with real method data flow.
 * A strict-dominator anchor is preferred so the generated definition crosses a CFG edge before
 * reaching the original constant's uses.
 */
public final class NumberObfuscationPass implements MethodPass {
    public static final String ID = "frost.obfuscate.numbers";

    private final Options options;

    public NumberObfuscationPass(Options options) {
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
        List<IrInstruction> candidates = method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::isNumericConstant)
                .filter(instruction -> instruction.result().isUsed())
                .toList();
        List<Value> anchors = collectOriginalAnchors(method, constants);
        int changed = 0;
        int entangled = 0;
        int crossBlock = 0;

        for (IrInstruction constant : candidates) {
            if (changed >= options.maximum() || random.nextInt(100) >= options.probability()) continue;
            BasicBlock target = constant.block().orElse(null);
            if (target == null || !dominators.isReachable(target)) continue;
            long original = ((IrAttribute.LongValue) constant.operation().attributes().get("value")).value();
            Value anchor = options.entangleWithDataFlow()
                    ? chooseAnchor(anchors, constant, target, dominators, random) : null;
            boolean crossed = anchor != null && anchorBlock(method, anchor) != target;
            Value replacement = anchor == null
                    ? rewriteWithConstantShares(method, constant, original, random)
                    : rewriteWithAnchor(method, constant, anchor, original, random);
            copyIdentity(constant, replacement);
            constant.result().replaceAllUsesWith(replacement);
            constant.erase();
            changed++;
            if (anchor != null) entangled++;
            if (crossed) crossBlock++;
        }

        if (changed == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of(
                "obfuscated", (long) changed,
                "data_flow_entangled", (long) entangled,
                "cross_block", (long) crossBlock));
    }

    private Value rewriteWithAnchor(IrMethod method, IrInstruction constant, Value anchor,
                                    long original, SplittableRandom random) {
        PrimitiveType type = (PrimitiveType) constant.result().type();
        BasicBlock target = constant.block().orElseThrow();
        BasicBlock preludeBlock = anchorBlock(method, anchor);
        int preludeIndex = insertionIndex(preludeBlock, anchor);
        IrExpressionBuilder prelude = new IrExpressionBuilder(method, preludeBlock, preludeIndex);
        long salt = randomNonZero(random, type);
        boolean xorVariant = random.nextBoolean();
        Value saltValue = prelude.constant(salt, type);
        Value masked = prelude.binary(xorVariant ? CoreOps.XOR : CoreOps.ADD,
                anchor, saltValue, type);

        int targetIndex = target.instructions().indexOf(constant);
        IrExpressionBuilder finish = new IrExpressionBuilder(method, target, targetIndex);
        Value recoveredShare = finish.binary(xorVariant ? CoreOps.XOR : CoreOps.SUB,
                masked, anchor, type);
        long encoded = xorVariant ? normalize(original ^ salt, type)
                : normalize(original - salt, type);
        Value encodedValue = finish.constant(encoded, type);
        return finish.binary(xorVariant ? CoreOps.XOR : CoreOps.ADD,
                recoveredShare, encodedValue, type);
    }

    private Value rewriteWithConstantShares(IrMethod method, IrInstruction constant,
                                            long original, SplittableRandom random) {
        PrimitiveType type = (PrimitiveType) constant.result().type();
        BasicBlock block = constant.block().orElseThrow();
        IrExpressionBuilder builder = new IrExpressionBuilder(method, block,
                block.instructions().indexOf(constant));
        long share = randomNonZero(random, type);
        boolean xorVariant = random.nextBoolean();
        Value first = builder.constant(share, type);
        Value second = builder.constant(xorVariant ? normalize(original ^ share, type)
                : normalize(original - share, type), type);
        return builder.binary(xorVariant ? CoreOps.XOR : CoreOps.ADD, first, second, type);
    }

    private Value chooseAnchor(List<Value> anchors, IrInstruction constant, BasicBlock target,
                               DominatorTree dominators, SplittableRandom random) {
        List<Value> eligible = anchors.stream()
                .filter(value -> value.type().equals(constant.result().type()))
                .filter(value -> dominatesUse(value, constant, target, dominators))
                .toList();
        if (eligible.isEmpty()) return null;
        List<Value> strict = eligible.stream()
                .filter(value -> anchorBlock(constant.method(), value) != target)
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
        if (definitionBlock != target) return true;
        if (definition instanceof PhiNode) return true;
        if (definition instanceof IrInstruction instruction) {
            return target.instructions().indexOf(instruction) < target.instructions().indexOf(use);
        }
        return false;
    }

    private List<Value> collectOriginalAnchors(IrMethod method, SparseConditionalConstants constants) {
        List<Value> values = new ArrayList<>();
        method.parameters().stream().map(MethodParameter::value).filter(this::isIntegerValue)
                .filter(value -> constants.fact(value) instanceof ConstantFact.Overdefined)
                .forEach(values::add);
        for (BasicBlock block : method.blocks()) {
            block.phis().stream().map(PhiNode::result).filter(this::isIntegerValue)
                    .filter(value -> constants.fact(value) instanceof ConstantFact.Overdefined)
                    .forEach(values::add);
            block.instructions().stream()
                    .filter(instruction -> !instruction.operation().code().equals(CoreOps.CONSTANT))
                    .flatMap(instruction -> instruction.results().stream())
                    .filter(this::isIntegerValue)
                    .filter(value -> constants.fact(value) instanceof ConstantFact.Overdefined)
                    .forEach(values::add);
        }
        return List.copyOf(values);
    }

    private boolean isNumericConstant(IrInstruction instruction) {
        return instruction.operation().code().equals(CoreOps.CONSTANT)
                && instruction.results().size() == 1
                && isIntegerValue(instruction.result())
                && instruction.operation().attributes().get("value") instanceof IrAttribute.LongValue;
    }

    private boolean isIntegerValue(Value value) {
        return value.type() == PrimitiveType.INT || value.type() == PrimitiveType.LONG;
    }

    private BasicBlock anchorBlock(IrMethod method, Value anchor) {
        return anchor.definition().definingBlock().orElseGet(() -> method.entryBlock().orElseThrow());
    }

    private int insertionIndex(BasicBlock block, Value anchor) {
        if (anchor.definition() instanceof IrInstruction instruction) {
            int index = block.instructions().indexOf(instruction);
            if (index >= 0) return index + 1;
        }
        return 0;
    }

    private long randomNonZero(SplittableRandom random, PrimitiveType type) {
        long value;
        do {
            value = type == PrimitiveType.INT ? random.nextInt() : random.nextLong();
        } while (value == 0);
        return normalize(value, type);
    }

    private long normalize(long value, PrimitiveType type) {
        return type == PrimitiveType.INT ? (int) value : value;
    }

    private void copyIdentity(IrInstruction original, Value replacement) {
        if (replacement.definition() instanceof IrInstruction root) {
            original.metadata().copyPersistentTo(root.metadata());
        }
        replacement.setDebugName(original.result().debugName());
    }

    public record Options(int probability, int maximum, boolean entangleWithDataFlow,
                          boolean spreadAcrossBlocks) {
        public Options {
            if (probability < 0 || probability > 100) throw new IllegalArgumentException("probability");
            if (maximum < 0) throw new IllegalArgumentException("maximum");
        }
    }
}
