package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class InvokeDynamicTransformerTest {

    @Test
    void parallelTransformationUsesStableTargetMethodMetadata() {
        ClassPool pool = new ClassPool();
        int classCount = 128;
        for (int index = 0; index < classCount; index++) {
            ClassNode node = new ClassNode();
            node.version = Opcodes.V17;
            node.name = "example/Indy" + index;
            node.superName = "java/lang/Object";
            node.access = Opcodes.ACC_PUBLIC;

            MethodNode target = method("target");
            MethodNode caller = method("caller");
            caller.instructions.insertBefore(caller.instructions.getFirst(), new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "example/Indy" + ((index + 1) % classCount),
                    "target", "()V", false));
            node.methods.add(target);
            node.methods.add(caller);
            pool.addClass(node.name, node);
        }
        pool.configureParallelism(true, 8, 1);

        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertDoesNotThrow(() -> new InvokeDynamicTransformer()
                            .transform(pool, new MappingCollector(), config)));
            for (ClassNode node : pool.getClasses()) {
                assertEquals(3, node.methods.size());
                assertInstanceOf(InvokeDynamicInsnNode.class,
                        node.methods.get(1).instructions.getFirst());
            }
        } finally {
            pool.closeParallelism();
        }
    }

    private MethodNode method(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }
}
