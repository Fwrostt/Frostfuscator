package dev.frost.obfuscator.transformer.funsies;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecompilerCrasherTest {

    @Test
    void testDecompilerCrasherInjectsBadTryCatch() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/CrasherTest";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "compute", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        DecompilerCrasherTransformer transformer = new DecompilerCrasherTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        transformer.transform(pool, new MappingCollector(), config);

        assertTrue(method.tryCatchBlocks.size() >= 2);
    }
}
