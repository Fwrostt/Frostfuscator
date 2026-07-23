package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PolymorphTransformerTest {

    @Test
    void testPolymorphSubstitutesOpcodes() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/PolyTest";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "add", "(II)I", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));

        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        PolymorphTransformer transformer = new PolymorphTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "seed", 888L));

        transformer.transform(pool, new MappingCollector(), config);

        // IADD should be substituted with a multi-opcode sequence
        assertTrue(method.instructions.size() > 4);
    }
}
