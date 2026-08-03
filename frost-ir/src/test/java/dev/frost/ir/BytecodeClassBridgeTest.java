package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.bytecode.BytecodeClassImporter;
import dev.frost.ir.bytecode.BytecodeClassLowerer;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.Operation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class BytecodeClassBridgeTest {
    @Test
    void returnsByteIdenticalClassWhenIrIsUnchanged() {
        byte[] original = sourceClass();
        var imported = new BytecodeClassImporter(IrContext.standard()).importClass(original);
        var lowered = new BytecodeClassLowerer().lower(imported);
        assertTrue(lowered.succeeded());
        assertTrue(lowered.exactOriginal());
        assertArrayEquals(original, lowered.output().orElseThrow());
    }

    @Test
    void rebuildsFramesPreservesClassMetadataAndExecutesMutation() throws Exception {
        byte[] original = sourceClass();
        var imported = new BytecodeClassImporter(IrContext.standard()).importClass(original);
        var choose = imported.methods().entrySet().stream().filter(entry -> entry.getKey().name().equals("choose"))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        var one = choose.method().blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.CONSTANT))
                .filter(instruction -> instruction.operation().attributes().get("value") instanceof IrAttribute.LongValue value
                        && value.value() == 1).findFirst().orElseThrow();
        one.setOperation(new Operation(CoreOps.CONSTANT, Map.of("value", IrAttribute.of(99L))));

        var lowered = new BytecodeClassLowerer().lower(imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        assertFalse(lowered.exactOriginal());
        ClassNode reparsed = new ClassNode();
        new ClassReader(lowered.output().orElseThrow()).accept(reparsed, ClassReader.EXPAND_FRAMES);
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;", reparsed.signature);
        assertEquals("Bridge.java", reparsed.sourceFile);
        assertNotNull(reparsed.visibleAnnotations);
        assertEquals("Lfixture/Marker;", reparsed.visibleAnnotations.getFirst().desc);
        MethodNode emittedChoose = reparsed.methods.stream().filter(method -> method.name.equals("choose")).findFirst().orElseThrow();
        assertTrue(java.util.stream.Stream.of(emittedChoose.instructions.toArray()).anyMatch(FrameNode.class::isInstance));

        byte[] bytes = lowered.output().orElseThrow();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass("fixture.ClassBridge", bytes, 0, bytes.length); }
        }.define();
        assertEquals(99, type.getMethod("choose", int.class).invoke(null, 1));
        assertEquals(2, type.getMethod("choose", int.class).invoke(null, 0));
    }

    private byte[] sourceClass() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = "fixture/ClassBridge";
        node.superName = "java/lang/Object";
        node.signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;";
        node.sourceFile = "Bridge.java";
        node.visibleAnnotations = new java.util.ArrayList<>();
        node.visibleAnnotations.add(new AnnotationNode("Lfixture/Marker;"));
        node.methods.add(constructor());
        node.methods.add(diamond());
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private MethodNode constructor() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
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
        return method;
    }
}
