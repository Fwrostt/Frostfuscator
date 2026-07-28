package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;
import java.time.Duration;

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

    @Test
    void parallelReferenceHidingUsesStableTargetMethodMetadata() {
        ClassPool pool = new ClassPool();
        int classCount = 128;
        for (int index = 0; index < classCount; index++) {
            ClassNode node = new ClassNode();
            node.version = Opcodes.V17;
            node.name = "com/example/Parallel" + index;
            node.superName = "java/lang/Object";
            node.access = Opcodes.ACC_PUBLIC;

            MethodNode target = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "target", "()V", null, null);
            target.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(target);

            MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "caller", "()V", null, null);
            caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/example/Parallel" + ((index + 1) % classCount), "target", "()V", false));
            caller.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(caller);
            pool.addClass(node.name, node);
        }
        pool.configureParallelism(true, 8, 1);

        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "max-per-class", 1));

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertDoesNotThrow(() -> new ReferenceHidingTransformer()
                            .transform(pool, new MappingCollector(), config)));
            assertTrue(pool.getClasses().stream().allMatch(node -> node.methods.size() == 3));
        } finally {
            pool.closeParallelism();
        }
    }
}
