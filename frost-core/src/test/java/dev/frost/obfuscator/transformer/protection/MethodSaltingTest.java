package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MethodSaltingTest {

    @Test
    void testMethodSaltingInjectsSalts() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/SaltedClass";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        MethodSaltingTransformer transformer = new MethodSaltingTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "max-salts", 3, "seed", 999L));

        int initialSize = method.instructions.size();
        transformer.transform(pool, new MappingCollector(), config);

        assertTrue(method.instructions.size() > initialSize);
    }
}
