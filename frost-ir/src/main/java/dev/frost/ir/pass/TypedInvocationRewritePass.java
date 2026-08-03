package dev.frost.ir.pass;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Operation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Rewrites typed field/invoke operations without reconstructing JVM operand-stack patterns. */
public final class TypedInvocationRewritePass implements MethodPass {
    public static final String ID = "frost.rewrite.typed-invocations";
    private final Rewriter rewriter;

    public TypedInvocationRewritePass(Rewriter rewriter) {
        this.rewriter = Objects.requireNonNull(rewriter, "rewriter");
    }

    @Override public String id() { return ID; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        List<IrInstruction> candidates = method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(this::memberOperation).toList();
        int changed = 0;
        for (IrInstruction instruction : candidates) {
            Optional<Operation> replacement = rewriter.rewrite(new Candidate(instruction,
                    text(instruction, "owner"), text(instruction, "name"), text(instruction, "descriptor"),
                    text(instruction, "invoke_kind"), bool(instruction, "interface")), context);
            if (replacement.isEmpty() || replacement.get().equals(instruction.operation())) continue;
            instruction.setOperation(replacement.get());
            changed++;
        }
        return changed == 0 ? PassResult.unchanged()
                : new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("rewritten", (long) changed));
    }

    private boolean memberOperation(IrInstruction instruction) {
        var code = instruction.operation().code();
        return code.equals(CoreOps.INVOKE) || code.equals(CoreOps.FIELD_LOAD)
                || code.equals(CoreOps.FIELD_STORE) || code.equals(CoreOps.STATIC_LOAD)
                || code.equals(CoreOps.STATIC_STORE);
    }

    private String text(IrInstruction instruction, String name) {
        return instruction.operation().attributes().get(name) instanceof IrAttribute.StringValue value
                ? value.value() : "";
    }

    private boolean bool(IrInstruction instruction, String name) {
        return instruction.operation().attributes().get(name) instanceof IrAttribute.BooleanValue value && value.value();
    }

    @FunctionalInterface public interface Rewriter {
        Optional<Operation> rewrite(Candidate candidate, PassContext context);
    }

    public record Candidate(IrInstruction instruction, String owner, String name, String descriptor,
                            String invokeKind, boolean interfaceOwner) {}
}
