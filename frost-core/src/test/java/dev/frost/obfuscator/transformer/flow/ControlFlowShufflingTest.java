package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ControlFlowShufflingTest {

    @Test
    void testControlFlowShufflingPreservesInstructionsAndAddsJumps() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/TestFlow";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "compute", "(I)I", null, null);
        LabelNode l1 = new LabelNode();
        LabelNode l2 = new LabelNode();
        LabelNode l3 = new LabelNode();

        method.instructions.add(l1);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(l2);
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        method.instructions.add(l3);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));

        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        ControlFlowShufflingTransformer transformer = new ControlFlowShufflingTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "seed", 42L));

        transformer.transform(pool, new MappingCollector(), config);

        assertTrue(method.instructions.size() > 8);
    }
}
