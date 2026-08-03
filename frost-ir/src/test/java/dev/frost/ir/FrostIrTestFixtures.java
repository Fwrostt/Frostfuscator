package dev.frost.ir;

import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodSignature;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.type.MethodType;
import dev.frost.ir.type.PrimitiveType;
import java.util.List;
import java.util.Map;

final class FrostIrTestFixtures {
    private FrostIrTestFixtures() {}

    static Diamond diamond() {
        IrMethod method = new IrMethod(IrContext.standard(), new MethodSignature("fixture/Diamond", "choose",
                new MethodType(List.of(PrimitiveType.INT), PrimitiveType.INT), 0x0008, null, List.of()));
        var input = method.addParameter("input", PrimitiveType.INT).value();
        BasicBlock entry = method.createBlock("entry");
        BasicBlock onTrue = method.createBlock("on_true");
        BasicBlock onFalse = method.createBlock("on_false");
        BasicBlock merge = method.createBlock("merge");

        entry.append(method.createInstruction(CoreOps.CONDITIONAL_BRANCH, List.of(input), List.of()));
        ControlEdge trueEdge = method.connect(entry, onTrue, EdgeKind.TRUE);
        ControlEdge falseEdge = method.connect(entry, onFalse, EdgeKind.FALSE);

        IrInstruction one = constant(method, 1);
        onTrue.append(one);
        onTrue.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
        ControlEdge trueMerge = method.connect(onTrue, merge, EdgeKind.NORMAL);

        IrInstruction two = constant(method, 2);
        onFalse.append(two);
        onFalse.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
        ControlEdge falseMerge = method.connect(onFalse, merge, EdgeKind.NORMAL);

        PhiNode result = merge.addPhi(PrimitiveType.INT, "result");
        result.putInput(trueMerge, one.result());
        result.putInput(falseMerge, two.result());
        merge.append(method.createInstruction(CoreOps.RETURN, List.of(result.result()), List.of()));
        return new Diamond(method, entry, onTrue, onFalse, merge, trueEdge, falseEdge,
                trueMerge, falseMerge, one, two, result);
    }

    static IrInstruction constant(IrMethod method, int value) {
        return method.createInstruction(new Operation(CoreOps.CONSTANT,
                Map.of("value", IrAttribute.of((long) value))), List.of(), List.of(PrimitiveType.INT));
    }

    record Diamond(IrMethod method, BasicBlock entry, BasicBlock onTrue, BasicBlock onFalse,
                   BasicBlock merge, ControlEdge trueEdge, ControlEdge falseEdge,
                   ControlEdge trueMerge, ControlEdge falseMerge, IrInstruction one,
                   IrInstruction two, PhiNode result) {}
}
