package dev.frost.obfuscator.transformer.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.Operation;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassResult;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

class IrMethodPassAdapterTest {
    @Test
    void passFailureLeavesTheSourceMethodUntouched() {
        MethodNode source = constantMethod();
        Object firstInstruction = source.instructions.getFirst();
        MethodPass failing = new MethodPass() {
            @Override public String id() { return "test.phase-two.failure"; }
            @Override public PassResult run(dev.frost.ir.model.IrMethod method,
                                            dev.frost.ir.pass.PassContext context) {
                throw new IllegalStateException("deliberate");
            }
        };

        var result = new IrMethodPassAdapter().run("fixture/AdapterFailure", source, failing, 7L);

        assertEquals(IrMethodPassAdapter.Status.PASS_FAILED, result.status());
        assertEquals(2, source.instructions.size());
        assertSame(firstInstruction, source.instructions.getFirst());
        assertEquals(Opcodes.BIPUSH, source.instructions.getFirst().getOpcode());
    }

    @Test
    void unsupportedImportLeavesUnreachableBytecodeUntouched() {
        MethodNode source = constantMethod();
        source.instructions.add(new InsnNode(Opcodes.ICONST_1));
        source.instructions.add(new InsnNode(Opcodes.IRETURN));
        MethodPass unchanged = new MethodPass() {
            @Override public String id() { return "test.phase-two.unchanged"; }
            @Override public PassResult run(dev.frost.ir.model.IrMethod method,
                                            dev.frost.ir.pass.PassContext context) {
                return PassResult.unchanged();
            }
        };

        var result = new IrMethodPassAdapter().run("fixture/AdapterUnsupported", source, unchanged, 8L);

        assertEquals(IrMethodPassAdapter.Status.UNSUPPORTED, result.status());
        assertEquals(4, source.instructions.size());
        assertEquals(Opcodes.ICONST_1, source.instructions.get(2).getOpcode());
    }

    @Test
    void loweringFailureDoesNotPublishTheMutatedIr() {
        MethodNode source = constantMethod();
        MethodPass opaque = new MethodPass() {
            @Override public String id() { return "test.phase-two.unlowerable"; }
            @Override public PassResult run(dev.frost.ir.model.IrMethod method,
                                            dev.frost.ir.pass.PassContext context) {
                var constant = method.blocks().stream().flatMap(block -> block.instructions().stream())
                        .filter(instruction -> instruction.operation().code().equals(CoreOps.CONSTANT))
                        .findFirst().orElseThrow();
                constant.setOperation(new Operation(CoreOps.OPAQUE_PURE_BYTECODE));
                return PassResult.modified();
            }
        };

        var result = new IrMethodPassAdapter().run("fixture/AdapterLowering", source, opaque, 9L);

        assertEquals(IrMethodPassAdapter.Status.LOWERING_FAILED, result.status());
        assertEquals(Opcodes.BIPUSH, source.instructions.getFirst().getOpcode());
        assertEquals(Opcodes.IRETURN, source.instructions.getLast().getOpcode());
    }

    @Test
    void oversizedFrameAnalysisFallsBackBeforeRunningThePass() {
        MethodNode source = constantMethod();
        source.maxLocals = 10_000;
        for (int index = 0; index < 600; index++) {
            source.instructions.insertBefore(source.instructions.getLast(), new InsnNode(Opcodes.NOP));
        }
        AtomicBoolean ran = new AtomicBoolean();
        MethodPass pass = new MethodPass() {
            @Override public String id() { return "test.analysis-budget"; }
            @Override public PassResult run(dev.frost.ir.model.IrMethod method,
                                            dev.frost.ir.pass.PassContext context) {
                ran.set(true);
                return PassResult.unchanged();
            }
        };

        var result = new IrMethodPassAdapter().run("fixture/AdapterBudget", source, pass, 10L);

        assertEquals(IrMethodPassAdapter.Status.UNSUPPORTED, result.status());
        assertFalse(ran.get());
        assertEquals(602, source.instructions.size());
    }

    private MethodNode constantMethod() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "answer", "()I", null, null);
        method.instructions.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 42));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        return method;
    }
}
