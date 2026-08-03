package dev.frost.ir.pass;

import dev.frost.ir.bytecode.AsmMetadataKeys;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.ReferenceType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds a typed catch-and-rethrow exception region over the existing method body. */
public final class FlowRangePass implements MethodPass {
    public static final String ID = "frost.obfuscate.flow-range";

    @Override public String id() { return ID; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (method.blocks().isEmpty()) return PassResult.unchanged();
        Set<BasicBlock> protectedBlocks = new LinkedHashSet<>(method.blocks());
        int priority = method.exceptionRegions().stream().mapToInt(region -> region.priority()).max().orElse(-1) + 1;
        ReferenceType throwable = new ReferenceType("java/lang/Throwable", Nullability.NON_NULL);
        BasicBlock handler = method.createBlock("frost_range_handler_" + priority);
        PhiNode caught = handler.addPhi(throwable, "frost_range_exception");
        caught.metadata().put(AsmMetadataKeys.PHI_SLOT_KIND, "stack");
        caught.metadata().put(AsmMetadataKeys.PHI_SLOT_INDEX, 0L);
        handler.append(method.createInstruction(CoreOps.THROW, List.of(caught.result()), List.of()));

        for (BasicBlock block : protectedBlocks) {
            ControlEdge edge = method.connect(block, handler, EdgeKind.EXCEPTION,
                    throwable.internalName(), throwable, priority);
            caught.putInput(edge, edge.addValue("exception", throwable).result());
        }
        method.addExceptionRegion(protectedBlocks, handler, throwable, priority);
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of("ranges", 1L));
    }
}
