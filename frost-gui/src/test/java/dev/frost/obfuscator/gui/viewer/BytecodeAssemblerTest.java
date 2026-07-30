package dev.frost.obfuscator.gui.viewer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

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
        assertEquals(List.of("NewValue"), stringConstants(result.bytecode(), "hello", "()Ljava/lang/String;"));
    }

    @Test
    void testAssembleModifiesEachLdcConstantByInstructionIndex() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V17;
        classNode.name = "com/example/Sample";
        classNode.superName = "java/lang/Object";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "values", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode("FirstOld"));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new LdcInsnNode(42));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new LdcInsnNode("SecondOld"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(method);

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);

        String asmText = textify(writer.toByteArray())
                .replace("LDC \"FirstOld\"", "LDC \"FirstNew\"")
                .replace("LDC \"SecondOld\"", "LDC \"SecondNew\"");

        BytecodeAssembler.AssemblyResult result = new BytecodeAssembler().assemble(writer.toByteArray(), asmText);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(List.of("FirstNew", "SecondNew"),
                stringConstants(result.bytecode(), "values", "()Ljava/lang/String;"));
    }

    private static List<String> stringConstants(byte[] bytecode, String methodName, String descriptor) {
        ClassNode result = new ClassNode();
        new ClassReader(bytecode).accept(result, 0);
        MethodNode method = result.methods.stream()
                .filter(candidate -> candidate.name.equals(methodName) && candidate.desc.equals(descriptor))
                .findFirst()
                .orElseThrow();
        List<String> constants = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                constants.add(value);
            }
        }
        return constants;
    }

    private static String textify(byte[] bytecode) {
        StringWriter output = new StringWriter();
        new ClassReader(bytecode).accept(new TraceClassVisitor(new PrintWriter(output)), 0);
        return output.toString();
    }
}
