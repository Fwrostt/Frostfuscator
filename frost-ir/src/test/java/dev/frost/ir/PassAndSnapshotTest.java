package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.analysis.StandardAnalyses;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.snapshot.IrFreezer;
import dev.frost.ir.text.IrDotExporter;
import dev.frost.ir.text.IrTextPrinter;
import org.junit.jupiter.api.Test;

class PassAndSnapshotTest {
    @Test
    void cachesAnalysesAndInvalidatesOnMutation() {
        var diamond = FrostIrTestFixtures.diamond();
        AnalysisManager analyses = new AnalysisManager();
        var first = analyses.get(diamond.method(), StandardAnalyses.DOMINATORS);
        var second = analyses.get(diamond.method(), StandardAnalyses.DOMINATORS);
        assertEquals(first, second);

        diamond.one().result().setDebugName("one");
        var third = analyses.get(diamond.method(), StandardAnalyses.DOMINATORS);
        assertNotSame(first, third);
    }

    @Test
    void enforcesPassChangedContract() {
        var diamond = FrostIrTestFixtures.diamond();
        MethodPass lyingPass = new MethodPass() {
            @Override public String id() { return "test.lying"; }
            @Override public PassResult run(dev.frost.ir.model.IrMethod method, dev.frost.ir.pass.PassContext context) {
                method.parameters().getFirst().value().setDebugName("renamed");
                return PassResult.unchanged();
            }
        };
        PassManager manager = new PassManager().add(lyingPass);
        assertThrows(IllegalStateException.class,
                () -> manager.run(diamond.method(), new PassContext(new AnalysisManager(), 42)));
    }

    @Test
    void freezesAndPrintsDeterministically() {
        var diamond = FrostIrTestFixtures.diamond();
        var frozen = new IrFreezer().freeze(diamond.method());
        assertEquals(4, frozen.blocks().size());
        assertEquals(diamond.method().revision(), frozen.sourceRevision());
        String first = new IrTextPrinter().print(diamond.method());
        String second = new IrTextPrinter().print(diamond.method());
        assertEquals(first, second);
        assertTrue(first.contains("phi"));
        assertTrue(first.contains("frost.control.conditional_branch"));
        String dot = new IrDotExporter().export(diamond.method());
        assertTrue(dot.contains("digraph frost_ir"));
        assertTrue(dot.contains("->"));
    }
}
