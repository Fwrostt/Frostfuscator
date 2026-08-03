package dev.frost.ir.pass;

import dev.frost.ir.bytecode.AsmMetadataKeys;
import dev.frost.ir.bytecode.JvmBootstrapAttributes;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.ReferenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ConstantDynamic;

/** Replaces selected source constants with fully IR-owned ConstantDynamic operations. */
public final class ConstantDynamicIndirectionPass implements MethodPass {
    public static final String ID = "frost.obfuscate.constant-dynamic";
    private final Map<Long, ConstantDynamic> replacementsBySourceIndex;
    private final Map<Long, MemberReplacement> memberReplacements;

    public ConstantDynamicIndirectionPass(Map<Long, ConstantDynamic> replacementsBySourceIndex) {
        this(replacementsBySourceIndex, Map.of());
    }

    public ConstantDynamicIndirectionPass(Map<Long, ConstantDynamic> replacementsBySourceIndex,
                                          Map<Long, MemberReplacement> memberReplacements) {
        this.replacementsBySourceIndex = Map.copyOf(replacementsBySourceIndex);
        this.memberReplacements = Map.copyOf(memberReplacements);
    }

    @Override public String id() { return ID; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        int changed = 0;
        int members = 0;
        for (IrInstruction instruction : method.blocks().stream().flatMap(block -> block.instructions().stream()).toList()) {
            long sourceIndex = instruction.metadata().get(AsmMetadataKeys.INSTRUCTION_INDEX).orElse(-1L);
            ConstantDynamic replacement = replacementsBySourceIndex.get(sourceIndex);
            if (replacement != null && (instruction.operation().code().equals(CoreOps.CONSTANT)
                    || instruction.operation().code().equals(CoreOps.CONSTANT_DYNAMIC))) {
                instruction.setOperation(new Operation(CoreOps.CONSTANT_DYNAMIC,
                        JvmBootstrapAttributes.dynamicConstant(replacement)));
                changed++;
                continue;
            }
            MemberReplacement member = memberReplacements.get(sourceIndex);
            if (member == null || !memberOperation(instruction)) continue;
            var block = instruction.block().orElseThrow();
            int insertion = block.instructions().indexOf(instruction);
            ReferenceType handleType = new ReferenceType(member.handleClass(), Nullability.NON_NULL);
            IrInstruction handle = method.createInstruction(new Operation(CoreOps.CONSTANT_DYNAMIC,
                    JvmBootstrapAttributes.dynamicConstant(member.dynamic())), List.of(), List.of(handleType));
            block.insert(insertion++, handle);
            List<Value> operands = new ArrayList<>();
            operands.add(handle.result());
            operands.addAll(instruction.operands());
            IrInstruction invoke = method.createInstruction(invokeOperation(member), operands,
                    instruction.results().stream().map(Value::type).toList());
            block.insert(insertion, invoke);
            instruction.metadata().copyPersistentTo(invoke.metadata());
            if (!instruction.results().isEmpty()) instruction.result().replaceAllUsesWith(invoke.result());
            instruction.erase();
            changed++;
            members++;
        }
        return changed == 0 ? PassResult.unchanged()
                : new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of(
                "constants", (long) (changed - members), "members", (long) members));
    }

    private boolean memberOperation(IrInstruction instruction) {
        var code = instruction.operation().code();
        return code.equals(CoreOps.INVOKE) || code.equals(CoreOps.FIELD_LOAD)
                || code.equals(CoreOps.FIELD_STORE) || code.equals(CoreOps.STATIC_LOAD)
                || code.equals(CoreOps.STATIC_STORE);
    }

    private Operation invokeOperation(MemberReplacement member) {
        return new Operation(CoreOps.INVOKE, Map.of(
                "owner", IrAttribute.of(member.handleClass()),
                "name", IrAttribute.of(member.invokeName()),
                "descriptor", IrAttribute.of(member.invokeDescriptor()),
                "invoke_kind", IrAttribute.of("INVOKEVIRTUAL"),
                "interface", IrAttribute.of(false)));
    }

    public record MemberReplacement(ConstantDynamic dynamic, String handleClass,
                                    String invokeName, String invokeDescriptor) {
        public MemberReplacement {
            Objects.requireNonNull(dynamic); Objects.requireNonNull(handleClass);
            Objects.requireNonNull(invokeName); Objects.requireNonNull(invokeDescriptor);
        }
    }
}
