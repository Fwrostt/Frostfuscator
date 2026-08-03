package dev.frost.obfuscator.transformer.phase5;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.Value;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts one pure SSA expression slice into a private static ASM helper shell. */
public final class SsaExpressionOutliningPass implements MethodPass {
    private final String owner;
    private final String helperName;
    private final int minimumInstructions;
    private final int maximumInstructions;
    private final int maximumCaptureSlots;
    private PhaseFiveExpression.Tree outlined;

    public SsaExpressionOutliningPass(String owner, String helperName, int minimumInstructions,
                                      int maximumInstructions, int maximumCaptureSlots) {
        this.owner = owner;
        this.helperName = helperName;
        this.minimumInstructions = Math.max(2, minimumInstructions);
        this.maximumInstructions = Math.max(this.minimumInstructions, maximumInstructions);
        this.maximumCaptureSlots = Math.max(1, maximumCaptureSlots);
    }

    @Override
    public String id() {
        return "phase5.expression-outlining";
    }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (!method.exceptionRegions().isEmpty()) return PassResult.unchanged();
        Candidate candidate = select(method);
        if (candidate == null) return PassResult.unchanged();
        PhaseFiveExpression.Tree tree = candidate.tree;
        BasicBlock block = candidate.root.block().orElseThrow();

        Map<String, IrAttribute> attributes = new LinkedHashMap<>();
        attributes.put("owner", new IrAttribute.StringValue(owner));
        attributes.put("name", new IrAttribute.StringValue(helperName));
        attributes.put("descriptor", new IrAttribute.StringValue(tree.descriptor()));
        attributes.put("invoke_kind", new IrAttribute.StringValue("INVOKESTATIC"));
        attributes.put("interface", new IrAttribute.BooleanValue(false));
        IrInstruction call = method.createInstruction(new Operation(CoreOps.INVOKE, attributes),
                tree.captures(), List.of(tree.root().type()));
        int rootIndex = block.instructions().indexOf(candidate.root);
        block.insert(rootIndex, call);
        candidate.root.result().replaceAllUsesWith(call.result());
        int erased = tree.eraseDeadDefinitions();
        outlined = tree;
        return new PassResult(true, PreservedAnalyses.none(), List.of(),
                Map.of("outlinedSlices", 1L, "erasedOperations", (long) erased,
                        "capturedValues", (long) tree.captures().size()));
    }

    public boolean outlined() {
        return outlined != null;
    }

    public MethodNode buildHelper() {
        if (outlined == null) throw new IllegalStateException("No expression was outlined");
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                helperName, outlined.descriptor(), null, null);
        outlined.emit(helper.instructions);
        helper.instructions.add(new InsnNode(
                outlined.type() == dev.frost.ir.type.PrimitiveType.LONG ? Opcodes.LRETURN : Opcodes.IRETURN));
        helper.maxLocals = outlined.captureSlots();
        helper.maxStack = Math.max(4, maximumInstructions + 2);
        return helper;
    }

    private Candidate select(IrMethod method) {
        Candidate best = null;
        for (BasicBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                if (instruction.isTerminator() || instruction.results().size() != 1
                        || !instruction.result().isUsed()) continue;
                var tree = PhaseFiveExpression.build(instruction.result(), maximumInstructions).orElse(null);
                if (tree == null || tree.size() < minimumInstructions
                        || tree.captureSlots() > maximumCaptureSlots
                        || !tree.definitions().contains(instruction)) continue;
                if (best == null || tree.size() > best.tree.size()) best = new Candidate(instruction, tree);
            }
        }
        return best;
    }

    private record Candidate(IrInstruction root, PhaseFiveExpression.Tree tree) {}
}
