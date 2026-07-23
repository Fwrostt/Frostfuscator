package dev.frost.obfuscator.gui.viewer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeAssemblerTest {

    @Test
    void testAssembleModifiesLdcConstant() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/Sample";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "hello", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode("OldValue"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(method);

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        byte[] original = writer.toByteArray();

        BytecodeAssembler assembler = new BytecodeAssembler();
        String asmText = """
                // class version 65.0 (65)
                public class com/example/Sample {
                  public hello()Ljava/lang/String;
                    LDC "NewValue"
                    ARETURN
                }
                """;

        BytecodeAssembler.AssemblyResult result = assembler.assemble(original, asmText);
        assertTrue(result.success());
        assertNotNull(result.bytecode());
        assertTrue(result.bytecode().length > 0);
    }
}
