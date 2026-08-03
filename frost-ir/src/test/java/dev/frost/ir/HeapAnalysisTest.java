package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.frost.ir.analysis.AliasAnalysis;
import dev.frost.ir.analysis.AliasResult;
import dev.frost.ir.analysis.EscapeAnalysis;
import dev.frost.ir.analysis.EscapeState;
import dev.frost.ir.analysis.MemoryDef;
import dev.frost.ir.analysis.MemoryLiveOnEntry;
import dev.frost.ir.analysis.MemorySSA;
import dev.frost.ir.analysis.MemoryUse;
import dev.frost.ir.analysis.SparseConditionalConstants;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class HeapAnalysisTest {
    @Test
    void memorySsaFindsNearestAliasingFieldDefinition() {
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/BoxOps", fieldFlow());
        var method = imported.method();
        AliasAnalysis alias = AliasAnalysis.compute(method, SparseConditionalConstants.compute(method));
        MemorySSA memory = MemorySSA.compute(method, alias);
        var loads = method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.FIELD_LOAD)).toList();
        var store = method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.FIELD_STORE)).findFirst().orElseThrow();

        MemoryUse first = assertInstanceOf(MemoryUse.class, memory.access(loads.get(0)).orElseThrow());
        MemoryUse second = assertInstanceOf(MemoryUse.class, memory.access(loads.get(1)).orElseThrow());
        assertInstanceOf(MemoryLiveOnEntry.class, memory.clobberingAccess(first));
        MemoryDef definition = assertInstanceOf(MemoryDef.class, memory.clobberingAccess(second));
        assertSame(store, definition.instruction());
    }

    @Test
    void distinguishesElementsOfDistinctFreshArrays() {
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Arrays", distinctArrays());
        var method = imported.method();
        AliasAnalysis alias = AliasAnalysis.compute(method, SparseConditionalConstants.compute(method));
        var loads = method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.ARRAY_LOAD)).toList();
        assertEquals(AliasResult.NO_ALIAS,
                alias.alias(alias.location(loads.get(0)).orElseThrow(), alias.location(loads.get(1)).orElseThrow()));
    }

    @Test
    void tracksAllocationAliasesAcrossInitializationAndEscapeBoundaries() {
        assertEquals(EscapeState.NO_ESCAPE, allocationState(localAllocation()));
        assertEquals(EscapeState.METHOD_ESCAPE, allocationState(returnedAllocation()));
        assertEquals(EscapeState.ARGUMENT_ESCAPE, allocationState(passedAllocation()));
    }

    private EscapeState allocationState(MethodNode source) {
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/Escape", source);
        EscapeAnalysis escapes = EscapeAnalysis.compute(imported.method());
        var allocation = escapes.allocationSites().stream().findFirst().orElseThrow();
        return escapes.stateOfAllocation(allocation);
    }

    private MethodNode fieldFlow() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "update", "(Lfixture/Box;I)I", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "fixture/Box", "x", "I"));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, "fixture/Box", "x", "I"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "fixture/Box", "x", "I"));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 3;
        method.maxStack = 3;
        return method;
    }

    private MethodNode distinctArrays() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()I", null, null);
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IALOAD));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IALOAD));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 3;
        return method;
    }

    private MethodNode localAllocation() { return allocationMethod("local"); }
    private MethodNode returnedAllocation() { return allocationMethod("return"); }
    private MethodNode passedAllocation() { return allocationMethod("pass"); }

    private MethodNode allocationMethod(String mode) {
        String descriptor = mode.equals("return") ? "()Ljava/lang/Object;" : "()V";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, mode, descriptor, null, null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        if (mode.equals("local")) {
            method.instructions.add(new InsnNode(Opcodes.POP));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        } else if (mode.equals("return")) {
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
        } else {
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "fixture/Escape", "consume", "(Ljava/lang/Object;)V", false));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        method.maxStack = 2;
        return method;
    }
}
