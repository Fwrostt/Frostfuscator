package dev.frost.obfuscator.gui.viewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveEditorWorkspaceTest {

    @Test
    void testDeleteMethodAndFieldOnClassNode(@TempDir Path tempDir) throws Exception {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/TestClass";
        classNode.access = Opcodes.ACC_PUBLIC;

        FieldNode field = new FieldNode(Opcodes.ACC_PUBLIC, "myField", "I", null, null);
        classNode.fields.add(field);

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "myMethod", "()V", null, null);
        classNode.methods.add(method);

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        byte[] bytes = writer.toByteArray();

        ArchiveEditorWorkspace workspace = new ArchiveEditorWorkspace(tempDir.resolve("dummy.jar"));
        workspace.updateClassBytes("com/example/TestClass.class", bytes);

        assertTrue(workspace.isModified("com/example/TestClass.class"));
        assertNotNull(workspace.getClassBytes("com/example/TestClass.class"));

        workspace.deleteField("com/example/TestClass.class", "myField", "I");
        byte[] fieldRemoved = workspace.getClassBytes("com/example/TestClass.class");
        assertNotNull(fieldRemoved);

        workspace.deleteMethod("com/example/TestClass.class", "myMethod", "()V");
        byte[] methodRemoved = workspace.getClassBytes("com/example/TestClass.class");
        assertNotNull(methodRemoved);
    }
}
