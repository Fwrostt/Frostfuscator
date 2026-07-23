package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FieldReferenceHidingTest {

    @Test
    void testFieldReferenceHidingProxiesFieldAccesses() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/FieldOwner";
        classNode.access = Opcodes.ACC_PUBLIC;

        FieldNode field = new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "I", null, 42);
        classNode.fields.add(field);

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "readField", "()I", null, null);
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "com/example/FieldOwner", "value", "I"));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        ReferenceHidingTransformer transformer = new ReferenceHidingTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        assertEquals(1, classNode.methods.size());
        transformer.transform(pool, new MappingCollector(), config);

        // A static field proxy method should be generated and GETSTATIC replaced by INVOKESTATIC
        assertEquals(2, classNode.methods.size());
        AbstractInsnNode firstInsn = method.instructions.getFirst();
        assertTrue(firstInsn instanceof MethodInsnNode);
        assertEquals(Opcodes.INVOKESTATIC, firstInsn.getOpcode());
    }
}
