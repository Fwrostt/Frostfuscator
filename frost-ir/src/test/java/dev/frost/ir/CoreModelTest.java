package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodSignature;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.OperationSchema;
import dev.frost.ir.model.OperationViolation;
import dev.frost.ir.type.MethodType;
import dev.frost.ir.type.ReferenceType;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.verify.IrValidator;
import dev.frost.ir.verify.ValidationProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreModelTest {
    @Test
    void maintainsOwnershipUseDefPhiAndRevisionInvariants() {
        var diamond = FrostIrTestFixtures.diamond();
        IrMethod method = diamond.method();

        var report = new IrValidator().validate(method, ValidationProfile.STRICT);
        assertTrue(report.isValid(), () -> report.diagnostics().toString());
        assertTrue(diamond.one().result().uses().stream().anyMatch(use -> use.user() == diamond.result()));
        assertSame(diamond.one().result(), diamond.result().input(diamond.trueMerge()).orElseThrow());

        long before = method.revision();
        try (IrMethod.Mutation ignored = method.beginMutation("test-batch")) {
            diamond.result().putInput(diamond.trueMerge(), diamond.two().result());
            diamond.result().putInput(diamond.trueMerge(), diamond.one().result());
        }
        assertEquals(before + 1, method.revision());
        assertTrue(new IrValidator().validate(method, ValidationProfile.STRICT).isValid());
    }

    @Test
    void rejectsCrossMethodOperandsAndLiveDefinitionErasure() {
        var diamond = FrostIrTestFixtures.diamond();
        IrMethod other = new IrMethod(IrContext.standard(), new MethodSignature("fixture/Other", "m",
                new MethodType(List.of(), PrimitiveType.VOID), 0x0008, null, List.of()));
        var block = other.createBlock("entry");
        assertThrows(IllegalArgumentException.class, () -> other.createInstruction(CoreOps.RETURN,
                List.of(diamond.one().result()), List.of()));
        block.append(other.createInstruction(CoreOps.RETURN, List.of(), List.of()));
        assertThrows(IllegalStateException.class, diamond.one()::erase);
    }

    @Test
    void replaceAllUsesUpdatesBothDirections() {
        var diamond = FrostIrTestFixtures.diamond();
        diamond.one().result().replaceAllUsesWith(diamond.two().result());
        assertFalse(diamond.one().result().isUsed());
        assertSame(diamond.two().result(), diamond.result().input(diamond.trueMerge()).orElseThrow());
    }

    @Test
    void validatesCoreTypesAndPluginOperationContracts() {
        IrContext.Builder context = IrContext.builder();
        CoreOps.schemas().forEach(context::register);
        OperationCode plugin = new OperationCode("test", "checked");
        context.register(OperationSchema.builder(plugin).operands(0).results(0)
                .verify(instruction -> List.of(new OperationViolation("test-contract", "plugin contract rejected operation")))
                .build());
        IrMethod method = new IrMethod(context.build(), new MethodSignature("fixture/Types", "bad",
                new MethodType(List.of(ReferenceType.OBJECT, ReferenceType.OBJECT), PrimitiveType.VOID),
                0x0008, null, List.of()));
        var left = method.addParameter("left", ReferenceType.OBJECT).value();
        var right = method.addParameter("right", ReferenceType.OBJECT).value();
        var entry = method.createBlock("entry");
        entry.append(method.createInstruction(CoreOps.ADD, List.of(left, right), List.of(ReferenceType.OBJECT)));
        entry.append(method.createInstruction(plugin, List.of(), List.of()));
        entry.append(method.createInstruction(CoreOps.RETURN, List.of(), List.of()));

        var report = new IrValidator().validate(method, ValidationProfile.STRICT);
        assertFalse(report.isValid());
        assertTrue(report.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("operation.type")));
        assertTrue(report.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("operation.test-contract")));
    }
}
