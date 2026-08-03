package dev.frost.obfuscator.transformer.flow;

import dev.frost.ir.analysis.AnalysisManager;
import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassManager;
import dev.frost.obfuscator.transformer.flow.ssa.SsaControlFlowShufflingPass;
import dev.frost.obfuscator.transformer.flow.ssa.SsaFlowConditionPass;
import dev.frost.obfuscator.transformer.flow.ssa.SsaFlowExceptionPass;
import dev.frost.obfuscator.transformer.flow.ssa.SsaFlowFlatteningPass;
import dev.frost.obfuscator.transformer.flow.ssa.SsaFlowSwitchPass;
import dev.frost.obfuscator.transformer.flow.ssa.SsaOpaquePredicatePass;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseThreeFourSsaFlowTest {
    @Test
    void opaquePredicateUsesARealParameterAndHonorsProbability() throws Exception {
        String owner = "fixture/PhaseThreeOpaque";
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod(owner, diamond());
        Value parameter = imported.method().parameters().getFirst().value();
        var result = new PassManager().add(new SsaOpaquePredicatePass(
                        true, false, 100, 1, 0x31415926,
                        "arithmetic", "", 0))
                .run(imported.method(), new PassContext(new AnalysisManager(), 100L));

        assertTrue(result.changed());
        assertEquals(1L, result.metrics().get("frost.flow.opaque-ssa").get("predicates"));
        var guard = imported.method().blocks().stream()
                .filter(block -> block.normalSuccessors().stream()
                        .anyMatch(edge -> edge.label().equals("opaque-live")))
                .findFirst().orElseThrow();
        Value predicate = guard.terminator().orElseThrow().operands().getFirst();
        assertTrue(dependsOn(predicate, parameter,
                Collections.newSetFromMap(new IdentityHashMap<>())),
                "the opaque invariant must depend on real method data");

        MethodNode lowered = new BytecodeMethodLowerer().lower(imported.method(), imported)
                .output().orElseThrow();
        verify(owner, lowered);
        assertSemantics(owner, lowered);

        var disabled = new BytecodeSsaImporter(IrContext.standard()).importMethod(owner, diamond());
        var disabledResult = new PassManager().add(new SsaOpaquePredicatePass(
                        true, false, 0, 1, 7, "arithmetic", "", 0))
                .run(disabled.method(), new PassContext(new AnalysisManager(), 101L));
        assertFalse(disabledResult.changed());
    }

    @Test
    void flowConditionRoundTripsADataDerivedGuard() throws Exception {
        String owner = "fixture/PhaseThreeCondition";
        MethodNode guarded = changed(new IrMethodPassAdapter().run(owner, diamond(),
                new SsaFlowConditionPass(100, 8, 17), 101));

        verify(owner, guarded);
        assertTrue(hasOpcode(guarded, Opcodes.IMUL) && hasOpcode(guarded, Opcodes.IAND));
        assertSemantics(owner, guarded);
    }

    @Test
    void phaseThreeAndBlockPassesRoundTripAStackPhiDiamond() throws Exception {
        String owner = "fixture/PhaseThreeFour";
        MethodNode method = diamond();
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();

        method = changed(adapter.run(owner, method, new SsaFlowConditionPass(100, 8, 17), 101));
        method = changed(adapter.run(owner, method, new SsaFlowSwitchPass(100), 102));
        method = changed(adapter.run(owner, method, new SsaFlowExceptionPass(100, 1, 23), 103));
        method = changed(adapter.run(owner, method, new SsaControlFlowShufflingPass(), 104));

        verify(owner, method);
        assertTrue(hasOpcode(method, Opcodes.IMUL) && hasOpcode(method, Opcodes.IAND),
                "Opaque conditions must be derived from real SSA values");
        assertTrue(hasOpcode(method, Opcodes.LOOKUPSWITCH), "Equality conditions should lower through a switch");
        assertTrue(method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty(),
                "Exception pass should lower an explicit synthetic handler region");
        assertSemantics(owner, method);
    }

    @Test
    void flatteningCarriesEdgePhiPayloadsThroughTheDispatcher() throws Exception {
        String owner = "fixture/FlattenedPhaseFour";
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();
        MethodNode flattened = changed(adapter.run(owner, diamond(),
                new SsaFlowFlatteningPass(3, 64, 0, 3), 4242));

        verify(owner, flattened);
        assertTrue(hasOpcode(flattened, Opcodes.LOOKUPSWITCH));
        assertSemantics(owner, flattened);
    }

    @Test
    void blockReorderingIsAnExactEntryFirstPermutationAndLowers() {
        String owner = "fixture/ReorderedPhaseFour";
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod(owner, diamond());
        var method = imported.method();
        List<dev.frost.ir.model.BasicBlock> reordered = new ArrayList<>(method.blocks());
        var moved = reordered.removeLast();
        reordered.add(1, moved);
        long before = method.revision();
        method.reorderBlocks(reordered);
        assertEquals(before + 1, method.revision());
        method.reorderBlocks(reordered);
        assertEquals(before + 1, method.revision(), "No-op reorders must not invalidate analyses");
        List<dev.frost.ir.model.BasicBlock> invalid = new ArrayList<>(reordered);
        invalid.set(1, invalid.getFirst());
        assertThrows(IllegalArgumentException.class, () -> method.reorderBlocks(invalid));
        assertTrue(new BytecodeMethodLowerer().lower(method, imported).succeeded());
    }

    private MethodNode changed(IrMethodPassAdapter.Result result) {
        assertEquals(IrMethodPassAdapter.Status.CHANGED, result.status(),
                () -> result.status() + ": " + result.message() + " " + result.diagnostics());
        return result.output().orElseThrow();
    }

    private MethodNode diamond() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "compute", "(I)I", null, null);
        LabelNode negative = new LabelNode(new Label());
        LabelNode even = new LabelNode(new Label());
        LabelNode join = new LabelNode(new Label());
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFLT, negative));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IAND));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, even));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_3));
        method.instructions.add(new InsnNode(Opcodes.IMUL));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(even);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(negative);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.INEG));
        method.instructions.add(join);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 1;
        method.maxStack = 2;
        return method;
    }

    private void assertSemantics(String owner, MethodNode method) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = owner;
        node.superName = "java/lang/Object";
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> loaded = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(owner.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
        Method compute = loaded.getMethod("compute", int.class);
        for (int value = -50; value <= 50; value++) {
            int expected = value < 0 ? -value : (value & 1) == 0 ? value + 2 : value * 3;
            assertEquals(expected, compute.invoke(null, value), "value=" + value);
        }
    }

    private void verify(String owner, MethodNode method) throws Exception {
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(owner, method);
    }

    private boolean hasOpcode(MethodNode method, int opcode) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) return true;
        }
        return false;
    }

    private boolean dependsOn(Value value, Value target, Set<Value> seen) {
        if (value == target) return true;
        if (!seen.add(value)) return false;
        if (value.definition() instanceof IrInstruction instruction) {
            return instruction.operands().stream().anyMatch(operand -> dependsOn(operand, target, seen));
        }
        if (value.definition() instanceof PhiNode phi) {
            return phi.inputs().values().stream().anyMatch(input -> dependsOn(input, target, seen));
        }
        return false;
    }
}
