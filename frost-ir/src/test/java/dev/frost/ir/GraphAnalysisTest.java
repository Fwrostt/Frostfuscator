package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.DominatorTree;
import dev.frost.ir.analysis.EdgePolicy;
import dev.frost.ir.analysis.Liveness;
import dev.frost.ir.analysis.LoopInfo;
import dev.frost.ir.analysis.PostDominatorTree;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphAnalysisTest {
    @Test
    void computesDiamondDominanceFrontiersPostDominanceAndPhiLiveness() {
        var diamond = FrostIrTestFixtures.diamond();
        DominatorTree dom = DominatorTree.compute(diamond.method(), EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        assertTrue(dom.dominates(diamond.entry(), diamond.merge()));
        assertFalse(dom.dominates(diamond.onTrue(), diamond.merge()));
        assertEquals(diamond.entry(), dom.immediateDominator(diamond.merge()).orElseThrow());
        assertTrue(dom.frontier(diamond.onTrue()).contains(diamond.merge()));
        assertTrue(dom.frontier(diamond.onFalse()).contains(diamond.merge()));

        PostDominatorTree post = PostDominatorTree.compute(diamond.method(), EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        assertTrue(post.postDominates(diamond.merge(), diamond.entry()));
        assertEquals(diamond.merge(), post.immediatePostDominator(diamond.entry()).orElseThrow());

        Liveness live = Liveness.compute(diamond.method(), EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        assertTrue(live.liveOut(diamond.onTrue()).contains(diamond.one().result()));
        assertTrue(live.phiUses(diamond.trueMerge()).contains(diamond.one().result()));
        assertFalse(live.liveIn(diamond.merge()).contains(diamond.result().result()));
    }

    @Test
    void detectsNaturalLoopAndNestingDepth() {
        IrMethod method = FrostIrTestFixtures.diamond().method();
        // Use an independent tiny loop to keep the fixture's valid diamond untouched.
        var context = method.context();
        IrMethod loopMethod = new IrMethod(context, method.signature());
        BasicBlock entry = loopMethod.createBlock("entry");
        BasicBlock header = loopMethod.createBlock("header");
        BasicBlock body = loopMethod.createBlock("body");
        BasicBlock exit = loopMethod.createBlock("exit");
        var condition = loopMethod.addParameter("condition", dev.frost.ir.type.PrimitiveType.INT).value();
        entry.append(loopMethod.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
        loopMethod.connect(entry, header, EdgeKind.NORMAL);
        header.append(loopMethod.createInstruction(CoreOps.CONDITIONAL_BRANCH, List.of(condition), List.of()));
        loopMethod.connect(header, body, EdgeKind.TRUE);
        loopMethod.connect(header, exit, EdgeKind.FALSE);
        body.append(loopMethod.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
        loopMethod.connect(body, header, EdgeKind.NORMAL);
        exit.append(loopMethod.createInstruction(CoreOps.RETURN, List.of(), List.of()));

        LoopInfo loops = LoopInfo.compute(loopMethod, EdgePolicy.NORMAL_ONLY);
        assertEquals(1, loops.loops().size());
        assertEquals(header, loops.loops().getFirst().header());
        assertTrue(loops.loops().getFirst().blocks().containsAll(List.of(header, body)));
        assertFalse(loops.loops().getFirst().irreducible());
        assertEquals(1, loops.depth(body));
    }
}
