package dev.frost.ir.pass;

import dev.frost.ir.bytecode.AsmMetadataKeys;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Replaces string literals with typed SSA invokes to ASM-generated reconstruction carriers. */
public final class StringReconstructionPass implements MethodPass {
    public static final String ID = "frost.obfuscate.string-splitting";
    private final Map<Long, List<Accessor>> accessorsBySourceIndex;

    public StringReconstructionPass(Map<Long, List<Accessor>> accessorsBySourceIndex) {
        Map<Long, List<Accessor>> copy = new LinkedHashMap<>();
        accessorsBySourceIndex.forEach((index, accessors) -> copy.put(index, List.copyOf(accessors)));
        this.accessorsBySourceIndex = Map.copyOf(copy);
    }

    @Override public String id() { return ID; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        int changed = 0;
        int fragments = 0;
        List<IrInstruction> constants = method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.CONSTANT)).toList();
        for (IrInstruction constant : constants) {
            long sourceIndex = constant.metadata().get(AsmMetadataKeys.INSTRUCTION_INDEX).orElse(-1L);
            List<Accessor> accessors = accessorsBySourceIndex.get(sourceIndex);
            if (accessors == null || accessors.isEmpty() || constant.results().size() != 1) continue;
            BasicBlock block = constant.block().orElseThrow();
            int insertion = block.instructions().indexOf(constant);
            Value reconstructed = null;
            for (Accessor accessor : accessors) {
                IrInstruction load = method.createInstruction(staticInvoke(accessor), List.of(),
                        List.of(constant.result().type()));
                block.insert(insertion++, load);
                if (reconstructed == null) {
                    reconstructed = load.result();
                } else {
                    IrInstruction concat = method.createInstruction(concatInvoke(),
                            List.of(reconstructed, load.result()), List.of(constant.result().type()));
                    block.insert(insertion++, concat);
                    reconstructed = concat.result();
                }
            }
            constant.metadata().copyPersistentTo(((IrInstruction) reconstructed.definition()).metadata());
            constant.result().replaceAllUsesWith(reconstructed);
            constant.erase();
            changed++;
            fragments += accessors.size();
        }
        return changed == 0 ? PassResult.unchanged()
                : new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of(
                "strings", (long) changed, "fragments", (long) fragments));
    }

    private Operation staticInvoke(Accessor accessor) {
        return new Operation(CoreOps.INVOKE, Map.of(
                "owner", IrAttribute.of(accessor.owner()), "name", IrAttribute.of(accessor.name()),
                "descriptor", IrAttribute.of(accessor.descriptor()),
                "invoke_kind", IrAttribute.of("INVOKESTATIC"), "interface", IrAttribute.of(false)));
    }

    private Operation concatInvoke() {
        return new Operation(CoreOps.INVOKE, Map.of(
                "owner", IrAttribute.of("java/lang/String"), "name", IrAttribute.of("concat"),
                "descriptor", IrAttribute.of("(Ljava/lang/String;)Ljava/lang/String;"),
                "invoke_kind", IrAttribute.of("INVOKEVIRTUAL"), "interface", IrAttribute.of(false)));
    }

    public record Accessor(String owner, String name, String descriptor) {
        public Accessor { Objects.requireNonNull(owner); Objects.requireNonNull(name); Objects.requireNonNull(descriptor); }
    }
}
