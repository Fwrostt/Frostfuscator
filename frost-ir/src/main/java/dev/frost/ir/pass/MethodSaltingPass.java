package dev.frost.ir.pass;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Value;
import java.util.List;
import java.util.Map;

/** Stack-independent salts expressed as SSA copies or explicit IR no-ops. */
public final class MethodSaltingPass implements MethodPass {
    public static final String ID = "frost.obfuscate.method-salting";
    private final int maximum;

    public MethodSaltingPass(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("maximum");
        this.maximum = maximum;
    }

    @Override public String id() { return ID; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (maximum == 0) return PassResult.unchanged();
        List<IrInstruction> candidates = method.blocks().stream()
                .flatMap(block -> block.instructions().stream()).toList();
        int added = 0;
        for (IrInstruction instruction : candidates) {
            if (added >= maximum) break;
            BasicBlock block = instruction.block().orElse(null);
            if (block == null) continue;
            int index = block.instructions().indexOf(instruction);
            if (instruction.operands().isEmpty()) {
                block.insert(index, method.createInstruction(CoreOps.NOP, List.of(), List.of()));
            } else {
                int operandIndex = context.randomFor(id() + "." + instruction.id()).nextInt(instruction.operands().size());
                Value operand = instruction.operands().get(operandIndex);
                IrInstruction copy = method.createInstruction(CoreOps.COPY, List.of(operand), List.of(operand.type()));
                block.insert(index, copy);
                instruction.setOperand(operandIndex, copy.result());
            }
            added++;
        }
        return added == 0 ? PassResult.unchanged()
                : new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("salts", (long) added));
    }
}
