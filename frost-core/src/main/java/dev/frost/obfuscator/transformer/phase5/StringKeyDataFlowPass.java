package dev.frost.obfuscator.transformer.phase5;

import dev.frost.ir.analysis.DominatorTree;
import dev.frost.ir.analysis.EdgePolicy;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodParameter;
import dev.frost.ir.model.Value;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import dev.frost.ir.type.PrimitiveType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Weaves string-decryption integer keys through dominating, non-constant application data. */
public final class StringKeyDataFlowPass implements MethodPass {
    private static final Set<String> SUPPORTED_DESCRIPTORS = Set.of(
            "([BI)Ljava/lang/String;",
            "([III)Ljava/lang/String;",
            "(Ljava/lang/String;I)Ljava/lang/String;"
    );

    private final String owner;
    private final Set<String> decryptMethods;
    private final int maximumSites;

    public StringKeyDataFlowPass(String owner, Set<String> decryptMethods, int maximumSites) {
        this.owner = owner;
        this.decryptMethods = Set.copyOf(decryptMethods);
        this.maximumSites = Math.max(0, maximumSites);
    }

    @Override
    public String id() {
        return "phase5.string-key-data-flow";
    }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (maximumSites == 0 || decryptMethods.isEmpty()) return PassResult.unchanged();
        DominatorTree dominators = DominatorTree.compute(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        int woven = 0;

        for (BasicBlock block : List.copyOf(method.blocks())) {
            for (IrInstruction call : List.copyOf(block.instructions())) {
                if (woven >= maximumSites) break;
                if (!isDecryptCall(call) || call.operands().size() < 2
                        || call.operands().get(1).type() != PrimitiveType.INT) continue;
                Value key = call.operands().get(1);
                Value contextValue = findContextValue(method, block, call, key, dominators);
                if (contextValue == null) continue;

                IrInstruction add = method.createInstruction(CoreOps.ADD,
                        List.of(key, contextValue), List.of(PrimitiveType.INT));
                IrInstruction subtract = method.createInstruction(CoreOps.SUB,
                        List.of(add.result(), contextValue), List.of(PrimitiveType.INT));
                int callIndex = block.instructions().indexOf(call);
                block.insert(callIndex, add);
                block.insert(callIndex + 1, subtract);
                call.setOperand(1, subtract.result());
                woven++;
            }
            if (woven >= maximumSites) break;
        }

        if (woven == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("wovenKeys", (long) woven));
    }

    private boolean isDecryptCall(IrInstruction instruction) {
        if (!instruction.operation().code().equals(CoreOps.INVOKE)) return false;
        return owner.equals(stringAttribute(instruction, "owner"))
                && decryptMethods.contains(stringAttribute(instruction, "name"))
                && SUPPORTED_DESCRIPTORS.contains(stringAttribute(instruction, "descriptor"))
                && "INVOKESTATIC".equals(stringAttribute(instruction, "invoke_kind"));
    }

    private Value findContextValue(IrMethod method, BasicBlock useBlock, IrInstruction use,
                                   Value key, DominatorTree dominators) {
        for (MethodParameter parameter : method.parameters()) {
            if (parameter.value() != key && parameter.value().type() == PrimitiveType.INT) {
                return parameter.value();
            }
        }
        for (BasicBlock candidateBlock : method.blocks()) {
            if (!dominators.dominates(candidateBlock, useBlock)) continue;
            for (IrInstruction candidate : candidateBlock.instructions()) {
                if (candidate == use && candidateBlock == useBlock) break;
                if (candidate.results().size() != 1 || candidate.result() == key
                        || candidate.result().type() != PrimitiveType.INT
                        || candidate.operation().code().equals(CoreOps.CONSTANT)) continue;
                return candidate.result();
            }
        }
        return null;
    }

    private String stringAttribute(IrInstruction instruction, String name) {
        IrAttribute value = instruction.operation().attributes().get(name);
        return value instanceof IrAttribute.StringValue text ? text.value() : "";
    }
}
