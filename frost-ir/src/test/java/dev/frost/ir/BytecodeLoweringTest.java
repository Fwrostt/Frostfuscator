package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.bytecode.BytecodeMethodLowerer;
import dev.frost.ir.bytecode.BytecodeSsaImporter;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.Operation;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.TypeAnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

class BytecodeLoweringTest {
    @Test
    void lowersMutatedDiamondDestroysPhisAndExecutes() throws Exception {
        MethodNode source = diamond();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/LoweredDiamond", source);
        assertTrue(imported.has(dev.frost.ir.bytecode.ImportCapability.TYPED_STACK_SSA));
        var constantOne = imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.CONSTANT))
                .filter(instruction -> instruction.operation().attributes().get("value") instanceof IrAttribute.LongValue value
                        && value.value() == 1).findFirst().orElseThrow();
        constantOne.setOperation(new Operation(CoreOps.CONSTANT, Map.of("value", IrAttribute.of(42L))));

        var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        Class<?> type = define("fixture/LoweredDiamond", lowered.output().orElseThrow());
        Method choose = type.getDeclaredMethod("choose", int.class);
        assertEquals(42, choose.invoke(null, 5));
        assertEquals(2, choose.invoke(null, 0));
    }

    @Test
    void lowersExceptionalHandlerPhisAndRetainsCatchSemantics() throws Exception {
        MethodNode source = arrayCatch();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/LoweredCatch", source);
        var minusOne = imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.CONSTANT))
                .filter(instruction -> instruction.operation().attributes().get("value") instanceof IrAttribute.LongValue value
                        && value.value() == -1).findFirst().orElseThrow();
        minusOne.setOperation(new Operation(CoreOps.CONSTANT, Map.of("value", IrAttribute.of(-7L))));

        var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        Class<?> type = define("fixture/LoweredCatch", lowered.output().orElseThrow());
        Method first = type.getDeclaredMethod("first", int[].class);
        assertEquals(9, first.invoke(null, (Object) new int[]{9}));
        assertEquals(-7, first.invoke(null, (Object) null));
        assertEquals(-7, first.invoke(null, (Object) new int[0]));
    }

    @Test
    void lowersLoopLocalPhisIincAndBackedgeCopies() throws Exception {
        MethodNode source = loopSum();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/LoweredLoop", source);
        imported.method().parameters().getFirst().value().setDebugName("limit");

        var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        Class<?> type = define("fixture/LoweredLoop", lowered.output().orElseThrow());
        Method sum = type.getDeclaredMethod("sum", int.class);
        assertEquals(15, sum.invoke(null, 5));
        assertEquals(0, sum.invoke(null, 0));
    }

    @Test
    void repeatedSsaRoundTripsDoNotAmplifyEmitterLocalTraffic() throws Exception {
        MethodNode current = loopSum();
        int firstLoweredSize = -1;
        int firstLoweredLocals = -1;
        java.util.List<String> sizes = new java.util.ArrayList<>();
        for (int round = 0; round < 8; round++) {
            var imported = new BytecodeSsaImporter(IrContext.standard())
                    .importMethod("fixture/StableRoundTrip", current);
            assertTrue(imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                            .noneMatch(instruction -> instruction.operation().code().equals(CoreOps.LOCAL_WRITE)),
                    "Physical JVM stores must not become persistent SSA operations");
            imported.method().parameters().getFirst().value().setDebugName("limit" + round);

            var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
            assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
            current = lowered.output().orElseThrow();
            if (firstLoweredSize < 0) firstLoweredSize = current.instructions.size();
            if (firstLoweredLocals < 0) firstLoweredLocals = current.maxLocals;
            java.util.Map<String, Long> kinds = java.util.stream.Stream.of(current.instructions.toArray())
                    .collect(java.util.stream.Collectors.groupingBy(
                            instruction -> instruction.getClass().getSimpleName() + "/" + instruction.getOpcode(),
                            java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
            sizes.add(current.instructions.size() + " insns/" + current.maxLocals + " locals:" + kinds);
        }
        assertTrue(current.instructions.size() <= firstLoweredSize * 2,
                "SSA round trips amplified instruction counts: " + sizes);
        assertTrue(current.maxLocals <= firstLoweredLocals,
                "SSA round trips amplified maxLocals: " + sizes);

        Class<?> type = define("fixture/StableRoundTrip", current);
        Method sum = type.getDeclaredMethod("sum", int.class);
        assertEquals(15, sum.invoke(null, 5));
    }

    @Test
    void lowersConstructorInitializationState() throws Exception {
        MethodNode source = constructor();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/LoweredConstructor", source);
        imported.method().parameters().getFirst().value().setDebugName("self");

        var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        Class<?> type = define("fixture/LoweredConstructor", lowered.output().orElseThrow());
        assertEquals(type, type.getDeclaredConstructor().newInstance().getClass());
    }

    @Test
    void reconstructsLineNumbersAndLocalVariableRanges() {
        MethodNode source = debugMethod();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/DebugInfo", source);
        imported.method().parameters().getFirst().value().setDebugName("renamed");

        var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        MethodNode output = lowered.output().orElseThrow();
        var lines = java.util.stream.Stream.of(output.instructions.toArray())
                .filter(LineNumberNode.class::isInstance).map(LineNumberNode.class::cast)
                .map(line -> line.line).toList();
        assertEquals(java.util.List.of(42, 43), lines);
        assertEquals(java.util.List.of("x", "result"), output.localVariables.stream().map(local -> local.name).toList());
        assertEquals("Lfixture/TypeUse;", output.visibleLocalVariableAnnotations.getFirst().desc);
    }

    @Test
    void reconstructsInstructionTypeAnnotations() {
        MethodNode source = annotatedCast();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/AnnotatedCast", source);
        imported.method().parameters().getFirst().value().setDebugName("value");

        var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        var cast = java.util.stream.Stream.of(lowered.output().orElseThrow().instructions.toArray())
                .filter(instruction -> instruction.getOpcode() == Opcodes.CHECKCAST).findFirst().orElseThrow();
        assertEquals("Lfixture/TypeUse;", cast.visibleTypeAnnotations.getFirst().desc);
    }

    @Test
    void normalizesLegacyJsrRetIntoTypedSsaAndModernBytecode() throws Exception {
        MethodNode source = legacySubroutine();
        var imported = new BytecodeSsaImporter(IrContext.standard()).importMethod("fixture/LegacySubroutine", source);
        assertTrue(imported.has(dev.frost.ir.bytecode.ImportCapability.TYPED_STACK_SSA),
                () -> imported.diagnostics().toString());
        assertTrue(imported.has(dev.frost.ir.bytecode.ImportCapability.LEGACY_SUBROUTINES_INLINED));
        imported.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.results().stream()).findFirst().orElseThrow().setDebugName("legacy");

        var lowered = new BytecodeMethodLowerer().lower(imported.method(), imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        assertTrue(java.util.stream.Stream.of(lowered.output().orElseThrow().instructions.toArray())
                .noneMatch(instruction -> instruction.getOpcode() == Opcodes.JSR || instruction.getOpcode() == Opcodes.RET));
        Class<?> type = define("fixture/LegacySubroutine", lowered.output().orElseThrow());
        assertEquals(1, type.getMethod("run").invoke(null));
    }

    private Class<?> define(String internalName, MethodNode method) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = internalName;
        node.superName = "java/lang/Object";
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }

    private MethodNode diamond() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose", "(I)I", null, null);
        LabelNode onFalse = new LabelNode();
        LabelNode merge = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, onFalse));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(onFalse);
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(merge);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        return method;
    }

    private MethodNode arrayCatch() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "first", "([I)I", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IALOAD));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));
        method.maxLocals = 1;
        method.maxStack = 2;
        return method;
    }

    private MethodNode loopSum() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sum", "(I)I", null, null);
        LabelNode header = new LabelNode();
        LabelNode exit = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(header);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFLE, exit));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new IincInsnNode(0, -1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, header));
        method.instructions.add(exit);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 2;
        return method;
    }

    private MethodNode constructor() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        return method;
    }

    private MethodNode debugMethod() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "increment", "(I)I", null, null);
        LabelNode start = new LabelNode();
        LabelNode resultStart = new LabelNode();
        LabelNode end = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new LineNumberNode(42, start));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(resultStart);
        method.instructions.add(new LineNumberNode(43, resultStart));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(end);
        method.localVariables.add(new LocalVariableNode("x", "I", null, start, end, 0));
        method.localVariables.add(new LocalVariableNode("result", "I", null, resultStart, end, 1));
        method.visibleLocalVariableAnnotations = new java.util.ArrayList<>();
        method.visibleLocalVariableAnnotations.add(new LocalVariableAnnotationNode(
                TypeReference.newTypeReference(TypeReference.LOCAL_VARIABLE).getValue(), null,
                new LabelNode[]{start}, new LabelNode[]{end}, new int[]{0}, "Lfixture/TypeUse;"));
        method.maxLocals = 2;
        method.maxStack = 2;
        return method;
    }

    private MethodNode annotatedCast() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "cast", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        org.objectweb.asm.tree.TypeInsnNode cast = new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String");
        cast.visibleTypeAnnotations = new java.util.ArrayList<>();
        cast.visibleTypeAnnotations.add(new TypeAnnotationNode(
                TypeReference.newTypeArgumentReference(TypeReference.CAST, 0).getValue(), null, "Lfixture/TypeUse;"));
        method.instructions.add(cast);
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        return method;
    }

    private MethodNode legacySubroutine() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
        LabelNode subroutine = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.JSR, subroutine));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(subroutine);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new IincInsnNode(0, 1));
        method.instructions.add(new VarInsnNode(Opcodes.RET, 1));
        method.maxLocals = 2;
        method.maxStack = 1;
        return method;
    }
}
