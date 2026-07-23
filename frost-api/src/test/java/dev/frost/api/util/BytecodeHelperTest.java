package dev.frost.api.util;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeHelperTest {

    @Test
    void testCreateDummyMethodAndFinders() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/TestClass";

        MethodNode dummy = BytecodeHelper.createDummyMethod("compute", "(I)I", Opcodes.ACC_PUBLIC);
        classNode.methods.add(dummy);

        assertTrue(BytecodeHelper.findMethod(classNode, "compute", "(I)I").isPresent());
        assertFalse(BytecodeHelper.findMethod(classNode, "nonExistent", null).isPresent());
        assertTrue(BytecodeHelper.isSynthetic(dummy.access));
    }

    @Test
    void testCreateIntConstantNodes() {
        assertNotNull(BytecodeHelper.createIntConstant(0));
        assertNotNull(BytecodeHelper.createIntConstant(100));
        assertNotNull(BytecodeHelper.createIntConstant(50000));
    }

    @Test
    void testClearDebugInfo() {
        ClassNode classNode = new ClassNode();
        classNode.sourceFile = "TestClass.java";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        classNode.methods.add(method);

        BytecodeHelper.clearDebugInfo(classNode);

        assertNull(classNode.sourceFile);
        assertNull(method.localVariables);
    }
}
